package net.derrek.bt4j.session;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import net.derrek.bt4j.metainfo.FileEntry;
import net.derrek.bt4j.metainfo.InfoHash;
import net.derrek.bt4j.metainfo.MagnetUri;
import net.derrek.bt4j.metainfo.Metainfo;
import net.derrek.bt4j.peer.Handshake;
import net.derrek.bt4j.peer.PeerAddress;
import net.derrek.bt4j.peer.PeerConnection;
import net.derrek.bt4j.peer.PeerId;
import net.derrek.bt4j.peer.PeerMessage;
import net.derrek.bt4j.peer.ext.ExtensionRegistry;
import net.derrek.bt4j.peer.ext.MetadataExchange;
import net.derrek.bt4j.piece.Bitfield;
import net.derrek.bt4j.piece.BlockRequest;
import net.derrek.bt4j.piece.PieceSelection;
import net.derrek.bt4j.piece.RarestFirstPicker;
import net.derrek.bt4j.storage.FileStorage;
import net.derrek.bt4j.storage.ResumeData;
import net.derrek.bt4j.tracker.AnnounceEvent;
import net.derrek.bt4j.tracker.AnnounceRequest;
import net.derrek.bt4j.tracker.Tracker;
import net.derrek.bt4j.tracker.TrackerManager;

/**
 * TorrentSession 的預設實作。
 * 來源可為 .torrent（直接 METADATA_READY）或磁力連結
 * （FETCHING_METADATA：以 tracker/x.pe 找 peer，經 BEP 10/9 取得 metadata 後轉 METADATA_READY）。
 */
final class DefaultTorrentSession implements TorrentSession {

    /** 每連線同時外送的 request 數（pipeline 深度）。 */
    private static final int PIPELINE_DEPTH = 16;

    private final InfoHash infoHash;
    private final PeerId peerId;
    private final int listenPort;
    private final int maxPeers;
    private final List<java.net.URI> magnetTrackers;
    private final MetadataExchange metadataExchange;
    private final java.util.concurrent.CompletableFuture<Metainfo> metadataFuture = new java.util.concurrent.CompletableFuture<>();

    private final List<SessionListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<PeerAddress, PeerWorker> workers = new ConcurrentHashMap<>();
    private final Set<PeerAddress> knownPeers = ConcurrentHashMap.newKeySet();
    private final BlockingQueue<PeerAddress> peerQueue = new LinkedBlockingQueue<>();
    private final Set<Integer> completedFileEvents = ConcurrentHashMap.newKeySet();
    private final AtomicLong downloadedBytes = new AtomicLong();
    private final AtomicLong uploadedBytes = new AtomicLong();
    private final AtomicLong verifiedWantedBytes = new AtomicLong();

    private final Object lock = new Object();
    private final Object listenerLock = new Object(); // 守護「加入 listener」與「補發 metadata 事件」的原子性
    private volatile SessionState state;
    private volatile Metainfo metainfo; // 磁力連結來源：取得 metadata 前為 null
    private volatile DownloadPlan plan;
    private volatile PieceSelection selection;
    private volatile FileStorage storage;
    private volatile RarestFirstPicker picker;
    private volatile TrackerManager trackerManager;
    private volatile Thread connectorThread;

    // 速率計算（stats() 呼叫間的差分）
    private long lastRateTime = System.nanoTime();
    private long lastDownloaded;
    private long lastUploaded;

    /** .torrent 來源。 */
    DefaultTorrentSession(Metainfo metainfo, PeerId peerId, int listenPort, int maxPeers) {
        this.infoHash = metainfo.infoHash();
        this.metainfo = metainfo;
        this.peerId = peerId;
        this.listenPort = listenPort;
        this.maxPeers = maxPeers;
        this.magnetTrackers = List.of();
        this.metadataExchange = new MetadataExchange(infoHash);
        this.metadataExchange.supply(metainfo.infoDictBytes()); // 可回應他人的 metadata request
        this.metadataFuture.complete(metainfo);
        this.state = SessionState.METADATA_READY;
    }

    private DefaultTorrentSession(MagnetUri magnet, PeerId peerId, int listenPort, int maxPeers) {
        this.infoHash = magnet.infoHash();
        this.peerId = peerId;
        this.listenPort = listenPort;
        this.maxPeers = maxPeers;
        this.magnetTrackers = magnet.trackers();
        this.metadataExchange = new MetadataExchange(infoHash);
        this.state = SessionState.FETCHING_METADATA;
    }

    /** 磁力連結來源：建立後立即開始背景取 metadata。 */
    static DefaultTorrentSession fromMagnet(MagnetUri magnet, PeerId peerId, int listenPort, int maxPeers) {
        DefaultTorrentSession session = new DefaultTorrentSession(magnet, peerId, listenPort, maxPeers);
        session.beginMetadataPhase(magnet);
        return session;
    }

    private void beginMetadataPhase(MagnetUri magnet) {
        metadataExchange.metadata().thenAccept(this::onMetadataFetched);
        onPeersFound(magnet.peers().stream().map(PeerAddress::new).toList());
        if (!magnetTrackers.isEmpty()) {
            trackerManager = new TrackerManager(
                    List.of(buildTrackerList(magnetTrackers)), this::request, this::onPeersFound);
            trackerManager.start();
        }
        connectorThread = Thread.ofVirtual().name("bt4j-connect-" + infoHash.hex()).start(this::connectorLoop);
    }

    private void onMetadataFetched(byte[] infoDictBytes) {
        Metainfo fetched;
        try {
            fetched = Metainfo.fromInfoDict(infoDictBytes, magnetTrackers);
        } catch (RuntimeException e) {
            fail(e);
            return;
        }
        synchronized (lock) {
            if (state != SessionState.FETCHING_METADATA) {
                return;
            }
            this.metainfo = fetched;
            teardownPeerMachinery(); // metadata 階段的連線收掉，下載階段以正確 pieceCount 重連
            setState(SessionState.METADATA_READY);
        }
        synchronized (listenerLock) {
            for (SessionListener listener : listeners) {
                listener.onMetadataReady(this, fetched);
            }
        }
        metadataFuture.complete(fetched);
    }

    // ---- TorrentSession ----

    @Override
    public InfoHash infoHash() {
        return infoHash;
    }

    @Override
    public SessionState state() {
        return state;
    }

    @Override
    public Optional<Metainfo> metadata() {
        return Optional.ofNullable(metainfo);
    }

    @Override
    public Metainfo awaitMetadata(Duration timeout) throws TimeoutException, InterruptedException {
        try {
            return metadataFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException("metadata 取得失敗", e.getCause());
        } catch (CancellationException e) {
            throw new IllegalStateException("session 已關閉", e);
        }
    }

    @Override
    public void start(DownloadPlan plan) {
        synchronized (lock) {
            if (state != SessionState.METADATA_READY) {
                throw new IllegalStateException("目前狀態 " + state + " 不可 start（變更計畫將於 M4 支援）");
            }
            this.plan = plan;
            this.selection = PieceSelection.of(metainfo, plan.selectedFileIndices());
            this.storage = new FileStorage(metainfo, selection, plan.saveTo());
            this.picker = new RarestFirstPicker(metainfo, selection, storage.completedPieces());
            setState(SessionState.DOWNLOADING);
            List<List<Tracker>> tiers = buildTiers();
            if (!tiers.isEmpty()) {
                this.trackerManager = new TrackerManager(tiers, this::request, this::onPeersFound);
                this.trackerManager.start();
            }
            this.connectorThread = Thread.ofVirtual().name("bt4j-connect-" + infoHash.hex()).start(this::connectorLoop);
        }
        // 磁力連結情境：metadata 階段已知的 peer 直接重新排入
        for (PeerAddress peer : knownPeers) {
            peerQueue.offer(peer);
        }
    }

    @Override
    public void stopSeeding() {
        synchronized (lock) {
            if (state == SessionState.STOPPED || state == SessionState.ERROR) {
                return;
            }
            setState(SessionState.STOPPED);
        }
        metadataFuture.cancel(true);
        teardownPeerMachinery();
    }

    @Override
    public TorrentStats stats() {
        PieceSelection sel = selection;
        long wanted = sel == null ? 0 : sel.wantedBytes();
        long verified = verifiedWantedBytes.get();
        long down = downloadedBytes.get();
        long up = uploadedBytes.get();
        long downRate;
        long upRate;
        synchronized (this) {
            long now = System.nanoTime();
            double seconds = Math.max((now - lastRateTime) / 1e9, 0.001);
            downRate = (long) ((down - lastDownloaded) / seconds);
            upRate = (long) ((up - lastUploaded) / seconds);
            lastRateTime = now;
            lastDownloaded = down;
            lastUploaded = up;
        }
        return new TorrentStats(verified, up, wanted,
                wanted == 0 ? 0.0 : (double) verified / wanted,
                workers.size(), downRate, upRate);
    }

    /**
     * 註冊事件監聽。若 metadata 已就緒，onMetadataReady 會立即補發給新 listener
     * （避免「addMagnet 後、addListener 前」metadata 已抓完造成漏事件——上層 UI 常見時序）。
     */
    @Override
    public void addListener(SessionListener listener) {
        synchronized (listenerLock) {
            listeners.add(listener);
            Metainfo meta = metainfo;
            if (meta != null) {
                listener.onMetadataReady(this, meta);
            }
        }
    }

    @Override
    public void removeListener(SessionListener listener) {
        listeners.remove(listener);
    }

    @Override
    public ResumeData resumeData() {
        Metainfo meta = metainfo;
        FileStorage st = storage;
        DownloadPlan p = plan;
        return new ResumeData(infoHash,
                st != null ? st.completedPieces() : new Bitfield(meta == null ? 1 : meta.pieceCount()),
                p == null ? Set.of() : p.selectedFileIndices(),
                p == null ? null : p.saveTo(),
                uploadedBytes.get(),
                state == SessionState.STOPPED);
    }

    @Override
    public void close() {
        stopSeeding();
        FileStorage st = storage;
        if (st != null) {
            try {
                st.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ---- 狀態與事件 ----

    private void setState(SessionState newState) {
        SessionState old = state;
        if (old == newState) {
            return;
        }
        state = newState;
        for (SessionListener listener : listeners) {
            listener.onStateChanged(this, old, newState);
        }
    }

    private void fail(Throwable error) {
        synchronized (lock) {
            if (state == SessionState.STOPPED || state == SessionState.ERROR) {
                return;
            }
            setState(SessionState.ERROR);
        }
        for (SessionListener listener : listeners) {
            listener.onError(this, error);
        }
        teardownPeerMachinery();
    }

    /** 收掉連線、tracker 排程與 connector（狀態不動，由呼叫端決定）。 */
    private void teardownPeerMachinery() {
        for (PeerWorker worker : workers.values()) {
            worker.connection.close();
        }
        workers.clear();
        TrackerManager tm = trackerManager;
        trackerManager = null;
        if (tm != null) {
            tm.close(); // 會在排程 thread 上盡力送 stopped
        }
        Thread c = connectorThread;
        connectorThread = null;
        if (c != null) {
            c.interrupt();
        }
    }

    // ---- tracker announce ----

    private void onPeersFound(List<PeerAddress> peers) {
        for (PeerAddress peer : peers) {
            if (knownPeers.add(peer)) {
                peerQueue.offer(peer);
            }
        }
    }

    private static List<Tracker> buildTrackerList(List<java.net.URI> uris) {
        List<Tracker> trackers = new ArrayList<>();
        for (java.net.URI uri : uris) {
            try {
                trackers.add(Tracker.of(uri));
            } catch (RuntimeException ignored) {
                // 不支援的 scheme（ws 等）
            }
        }
        return trackers;
    }

    /** BEP 12 tier 結構（tier 內順序由 TrackerManager 洗牌與晉升管理）。 */
    private List<List<Tracker>> buildTiers() {
        List<List<Tracker>> tiers = new ArrayList<>();
        for (List<java.net.URI> tier : metainfo.announceList()) {
            List<Tracker> trackers = buildTrackerList(tier);
            if (!trackers.isEmpty()) {
                tiers.add(trackers);
            }
        }
        return tiers;
    }

    private AnnounceRequest request(AnnounceEvent event) {
        PieceSelection sel = selection;
        long left = sel != null ? Math.max(0, sel.wantedBytes() - verifiedWantedBytes.get())
                : metainfo != null ? metainfo.totalLength()
                : 1; // metadata 未知：報 1 表示還沒完成
        return new AnnounceRequest(infoHash, peerId, listenPort,
                uploadedBytes.get(), downloadedBytes.get(), left, event, 50);
    }

    // ---- peer 連線管理 ----

    private void connectorLoop() {
        while (state == SessionState.FETCHING_METADATA || state == SessionState.DOWNLOADING) {
            PeerAddress address;
            try {
                address = peerQueue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (address == null || workers.size() >= maxPeers) {
                continue;
            }
            PeerWorker worker = new PeerWorker(address);
            if (workers.putIfAbsent(address, worker) == null) {
                worker.connection.start();
            }
        }
    }

    /** 單一 peer 連線邏輯。downloadMode 在建立時固定（metadata 階段的連線於轉換時收掉重連）。 */
    private final class PeerWorker implements PeerConnection.Listener {

        final PeerConnection connection;
        final ExtensionRegistry registry = new ExtensionRegistry(List.of(metadataExchange));
        final Set<BlockRequest> outstanding = ConcurrentHashMap.newKeySet();
        final boolean downloadMode;

        PeerWorker(PeerAddress address) {
            Metainfo meta = metainfo;
            this.downloadMode = meta != null;
            this.connection = PeerConnection.outgoing(
                    address, infoHash, peerId, downloadMode ? meta.pieceCount() : 0, this);
        }

        @Override
        public void onHandshakeCompleted(PeerConnection conn, Handshake theirs) {
            if (theirs.supportsExtensionProtocol()) {
                conn.send(registry.buildHandshake(metadataExchange.metadataSize().orElse(null)));
            }
            if (downloadMode) {
                Bitfield have = storage.completedPieces();
                if (have.cardinality() > 0) {
                    conn.send(new PeerMessage.BitfieldMessage(have));
                }
                conn.send(new PeerMessage.Interested());
            }
        }

        @Override
        public void onMessage(PeerConnection conn, PeerMessage message) {
            if (message instanceof PeerMessage.Extended extended) {
                registry.dispatch(conn, extended);
                return;
            }
            if (!downloadMode) {
                return; // metadata 階段只關心擴充訊息
            }
            switch (message) {
                case PeerMessage.Unchoke() -> fillPipeline();
                case PeerMessage.Choke() -> abandonOutstanding();
                case PeerMessage.BitfieldMessage(Bitfield bf) -> picker.onPeerBitfield(bf);
                case PeerMessage.Have(int piece) -> picker.onPeerHave(piece);
                case PeerMessage.Piece(int piece, int begin, byte[] data) -> onBlock(piece, begin, data);
                default -> {
                }
            }
        }

        @Override
        public void onClosed(PeerConnection conn, IOException error) {
            workers.remove(conn.address());
            if (downloadMode) {
                abandonOutstanding();
                picker.onPeerGone(conn.peerBitfield());
            }
        }

        private void fillPipeline() {
            if (connection.peerChoking() || state != SessionState.DOWNLOADING) {
                return;
            }
            int want = PIPELINE_DEPTH - outstanding.size();
            if (want <= 0) {
                return;
            }
            for (BlockRequest block : picker.pick(connection.peerBitfield(), want)) {
                if (outstanding.add(block)) {
                    connection.send(new PeerMessage.Request(block.pieceIndex(), block.begin(), block.length()));
                }
            }
        }

        private void abandonOutstanding() {
            List<BlockRequest> lost = new ArrayList<>(outstanding);
            outstanding.clear();
            picker.onRequestsAbandoned(lost);
        }

        private void onBlock(int pieceIndex, int begin, byte[] data) {
            outstanding.remove(new BlockRequest(pieceIndex, begin, data.length));
            downloadedBytes.addAndGet(data.length);
            storage.write(pieceIndex, begin, data);
            picker.onBlockReceived(new BlockRequest(pieceIndex, begin, data.length))
                    .ifPresent(this::completePiece);
            fillPipeline();
        }

        private void completePiece(int pieceIndex) {
            boolean valid;
            try {
                valid = storage.verifyPiece(pieceIndex);
            } catch (IOException e) {
                fail(e);
                return;
            }
            picker.onPieceVerified(pieceIndex, valid);
            if (!valid) {
                return; // 該 piece 重新排入；壞 peer 黑名單於 M9
            }
            verifiedWantedBytes.addAndGet(selection.wantedBytesInPiece(pieceIndex));
            broadcastHave(pieceIndex);
            fireFileCompletions(pieceIndex);
            if (picker.isComplete()) {
                onDownloadComplete();
            }
        }
    }

    private void broadcastHave(int pieceIndex) {
        for (PeerWorker worker : workers.values()) {
            worker.connection.send(new PeerMessage.Have(pieceIndex));
        }
    }

    private void fireFileCompletions(int verifiedPiece) {
        long pieceLength = metainfo.pieceLength();
        Bitfield completed = storage.completedPieces();
        for (FileEntry file : metainfo.files()) {
            if (!selection.isFileWanted(file.index()) || completedFileEvents.contains(file.index())) {
                continue;
            }
            long fileStart = file.offset();
            long fileEnd = file.offset() + Math.max(file.length(), 1);
            int firstPiece = (int) (fileStart / pieceLength);
            int lastPiece = (int) ((fileEnd - 1) / pieceLength);
            if (verifiedPiece < firstPiece || verifiedPiece > lastPiece) {
                continue;
            }
            boolean allDone = true;
            for (int p = firstPiece; p <= lastPiece && allDone; p++) {
                allDone = completed.get(p);
            }
            if (allDone && completedFileEvents.add(file.index())) {
                for (SessionListener listener : listeners) {
                    listener.onFileCompleted(this, file);
                }
            }
        }
    }

    private void onDownloadComplete() {
        synchronized (lock) {
            if (state != SessionState.DOWNLOADING) {
                return;
            }
            setState(SessionState.SEEDING);
        }
        TrackerManager tm = trackerManager;
        if (tm != null) {
            tm.announceCompleted();
        }
        for (SessionListener listener : listeners) {
            listener.onDownloadCompleted(this);
        }
    }

    @Override
    public String toString() {
        Metainfo meta = metainfo;
        return "TorrentSession[" + (meta != null ? meta.name() : infoHash.hex()) + ", " + state + "]";
    }
}
