package net.derrek.bt4j.session;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.derrek.bt4j.metainfo.FileEntry;
import net.derrek.bt4j.metainfo.InfoHash;
import net.derrek.bt4j.metainfo.Metainfo;
import net.derrek.bt4j.peer.Handshake;
import net.derrek.bt4j.peer.PeerAddress;
import net.derrek.bt4j.peer.PeerConnection;
import net.derrek.bt4j.peer.PeerId;
import net.derrek.bt4j.peer.PeerMessage;
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
 * TorrentSession 的預設實作（M3：.torrent 來源、下載與驗證；magnet 於 M6、上傳於 M8）。
 */
final class DefaultTorrentSession implements TorrentSession {

    /** 每連線同時外送的 request 數（pipeline 深度）。 */
    private static final int PIPELINE_DEPTH = 16;

    private final Metainfo metainfo;
    private final PeerId peerId;
    private final int listenPort;
    private final int maxPeers;

    private final List<SessionListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<PeerAddress, PeerWorker> workers = new ConcurrentHashMap<>();
    private final Set<PeerAddress> knownPeers = ConcurrentHashMap.newKeySet();
    private final BlockingQueue<PeerAddress> peerQueue = new LinkedBlockingQueue<>();
    private final Set<Integer> completedFileEvents = ConcurrentHashMap.newKeySet();
    private final AtomicLong downloadedBytes = new AtomicLong();
    private final AtomicLong uploadedBytes = new AtomicLong();
    private final AtomicLong verifiedWantedBytes = new AtomicLong();

    private final Object lock = new Object();
    private volatile SessionState state = SessionState.METADATA_READY;
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

    DefaultTorrentSession(Metainfo metainfo, PeerId peerId, int listenPort, int maxPeers) {
        this.metainfo = metainfo;
        this.peerId = peerId;
        this.listenPort = listenPort;
        this.maxPeers = maxPeers;
    }

    // ---- TorrentSession ----

    @Override
    public InfoHash infoHash() {
        return metainfo.infoHash();
    }

    @Override
    public SessionState state() {
        return state;
    }

    @Override
    public Optional<Metainfo> metadata() {
        return Optional.of(metainfo);
    }

    @Override
    public Metainfo awaitMetadata(Duration timeout) {
        return metainfo; // .torrent 來源：立即可用
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
            this.trackerManager = new TrackerManager(buildTiers(), this::request, this::onPeersFound);
            this.trackerManager.start();
            this.connectorThread = Thread.ofVirtual().name("bt4j-connect-" + infoHash().hex()).start(this::connectorLoop);
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
        shutdownWorkers();
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

    @Override
    public void addListener(SessionListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(SessionListener listener) {
        listeners.remove(listener);
    }

    @Override
    public ResumeData resumeData() {
        FileStorage st = storage;
        DownloadPlan p = plan;
        return new ResumeData(infoHash(),
                st == null ? new Bitfield(metainfo.pieceCount()) : st.completedPieces(),
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
        shutdownWorkers();
    }

    private void shutdownWorkers() {
        for (PeerWorker worker : workers.values()) {
            worker.connection.close();
        }
        workers.clear();
        TrackerManager tm = trackerManager;
        if (tm != null) {
            tm.close(); // 會在排程 thread 上盡力送 stopped
        }
        Thread c = connectorThread;
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

    /** BEP 12 tier 結構（tier 內順序由 TrackerManager 洗牌與晉升管理）。不支援的 scheme 個別略過。 */
    private List<List<Tracker>> buildTiers() {
        List<List<Tracker>> tiers = new ArrayList<>();
        for (List<java.net.URI> tier : metainfo.announceList()) {
            List<Tracker> trackers = new ArrayList<>();
            for (java.net.URI uri : tier) {
                try {
                    trackers.add(Tracker.of(uri));
                } catch (RuntimeException ignored) {
                    // 不支援的 scheme（ws 等）
                }
            }
            if (!trackers.isEmpty()) {
                tiers.add(trackers);
            }
        }
        return tiers;
    }

    private AnnounceRequest request(AnnounceEvent event) {
        PieceSelection sel = selection;
        long left = sel == null ? metainfo.totalLength()
                : Math.max(0, sel.wantedBytes() - verifiedWantedBytes.get());
        return new AnnounceRequest(infoHash(), peerId, listenPort,
                uploadedBytes.get(), downloadedBytes.get(), left, event, 50);
    }

    // ---- peer 連線管理 ----

    private void connectorLoop() {
        while (state == SessionState.DOWNLOADING) {
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

    /** 單一 peer 連線的下載邏輯。回呼在該連線的讀迴圈 thread 上執行。 */
    private final class PeerWorker implements PeerConnection.Listener {

        final PeerConnection connection;
        final Set<BlockRequest> outstanding = ConcurrentHashMap.newKeySet();

        PeerWorker(PeerAddress address) {
            this.connection = PeerConnection.outgoing(
                    address, infoHash(), peerId, metainfo.pieceCount(), this);
        }

        @Override
        public void onHandshakeCompleted(PeerConnection conn, Handshake theirs) {
            Bitfield have = storage.completedPieces();
            if (have.cardinality() > 0) {
                conn.send(new PeerMessage.BitfieldMessage(have));
            }
            conn.send(new PeerMessage.Interested());
        }

        @Override
        public void onMessage(PeerConnection conn, PeerMessage message) {
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
            abandonOutstanding();
            picker.onPeerGone(conn.peerBitfield());
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
                return; // 該 piece 重新排入，可能是壞 peer；M9 加入 peer 黑名單
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
        return "TorrentSession[" + metainfo.name() + ", " + state + "]";
    }
}
