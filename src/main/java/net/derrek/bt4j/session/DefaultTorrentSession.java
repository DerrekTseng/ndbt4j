package net.derrek.bt4j.session;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import net.derrek.bt4j.dht.DhtClient;
import net.derrek.bt4j.metainfo.FileEntry;
import net.derrek.bt4j.metainfo.InfoHash;
import net.derrek.bt4j.metainfo.MagnetUri;
import net.derrek.bt4j.metainfo.Metainfo;
import net.derrek.bt4j.peer.Handshake;
import net.derrek.bt4j.peer.PeerAddress;
import net.derrek.bt4j.peer.PeerConnection;
import net.derrek.bt4j.peer.PeerId;
import net.derrek.bt4j.peer.PeerMessage;
import net.derrek.bt4j.peer.ext.Extension;
import net.derrek.bt4j.peer.ext.ExtensionRegistry;
import net.derrek.bt4j.peer.ext.MetadataExchange;
import net.derrek.bt4j.peer.ext.PeerExchange;
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
 * 來源可為 .torrent（直接 METADATA_READY）、磁力連結（FETCHING_METADATA → BEP 10/9 取 metadata），
 * 或 resume 資料（重啟續傳，跳過已完成 piece）。
 * 下載完成後進入 SEEDING 並回應他人的 Request（上傳）；stopSeeding 手動關閉上傳。
 */
final class DefaultTorrentSession implements TorrentSession {

    private static final Logger LOG = System.getLogger(DefaultTorrentSession.class.getName());

    /** 每連線同時外送的 request 數（pipeline 深度）。 */
    private static final int PIPELINE_DEPTH = 16;
    /** 單一上傳 block 的長度上限（防惡意 Request 要求超大區塊）。 */
    private static final int MAX_UPLOAD_BLOCK = 128 * 1024;

    private final InfoHash infoHash;
    private final PeerId peerId;
    private final int listenPort;
    private final int maxPeers;
    private final DhtClient dht; // null = 停用
    private final net.derrek.bt4j.util.RateLimiter downloadLimiter;
    private final net.derrek.bt4j.util.RateLimiter uploadLimiter;
    private final List<java.net.URI> magnetTrackers;
    private final MetadataExchange metadataExchange;
    private final CompletableFuture<Metainfo> metadataFuture = new CompletableFuture<>();

    /** 一個 peer 連續造成 piece 驗證失敗達此次數即封鎖。 */
    private static final int MAX_PEER_STRIKES = 3;

    private final List<SessionListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<PeerAddress, PeerWorker> workers = new ConcurrentHashMap<>();
    private final Set<PeerAddress> knownPeers = ConcurrentHashMap.newKeySet();
    private final BlockingQueue<PeerAddress> peerQueue = new LinkedBlockingQueue<>();
    private final Set<Integer> completedFileEvents = ConcurrentHashMap.newKeySet();
    private final AtomicLong downloadedBytes = new AtomicLong();
    private final AtomicLong uploadedBytes = new AtomicLong();
    private final AtomicLong verifiedWantedBytes = new AtomicLong();

    // 壞 peer 追蹤：piece → 貢獻過 block 的 peer；每個 peer 的驗證失敗次數；封鎖名單
    private final Map<Integer, Set<PeerAddress>> pieceContributors = new ConcurrentHashMap<>();
    private final Map<PeerAddress, Integer> peerStrikes = new ConcurrentHashMap<>();
    private final Set<PeerAddress> bannedPeers = ConcurrentHashMap.newKeySet();

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
    private volatile Thread dhtThread;
    private volatile Thread pexThread;

    // 速率計算：每 ≥0.5 秒才重新取樣一次差分，避免同一輪多個 getter 各自呼叫 stats()
    // 把基準重設在幾微秒的區間內、算出 0 或雜訊速率。
    private static final long RATE_SAMPLE_NANOS = 500_000_000L;
    private long lastRateTime = System.nanoTime();
    private long lastDownloaded;
    private long lastUploaded;
    private long cachedDownloadRate;
    private long cachedUploadRate;

    /** BtClient 層級的共用執行環境（peer id、listen port、DHT、限速器等）。 */
    record Runtime(PeerId peerId, int listenPort, int maxPeers, DhtClient dht,
                   net.derrek.bt4j.util.RateLimiter downloadLimiter,
                   net.derrek.bt4j.util.RateLimiter uploadLimiter) {
    }

    /** .torrent 來源。 */
    DefaultTorrentSession(Metainfo metainfo, Runtime runtime) {
        this.infoHash = metainfo.infoHash();
        this.metainfo = metainfo;
        this.peerId = runtime.peerId();
        this.listenPort = runtime.listenPort();
        this.maxPeers = runtime.maxPeers();
        this.dht = runtime.dht();
        this.downloadLimiter = runtime.downloadLimiter();
        this.uploadLimiter = runtime.uploadLimiter();
        this.magnetTrackers = List.of();
        this.metadataExchange = new MetadataExchange(infoHash);
        this.metadataExchange.supply(metainfo.infoDictBytes()); // 可回應他人的 metadata request
        this.metadataFuture.complete(metainfo);
        this.state = SessionState.METADATA_READY;
    }

    private DefaultTorrentSession(MagnetUri magnet, Runtime runtime) {
        this.infoHash = magnet.infoHash();
        this.peerId = runtime.peerId();
        this.listenPort = runtime.listenPort();
        this.maxPeers = runtime.maxPeers();
        this.dht = runtime.dht();
        this.downloadLimiter = runtime.downloadLimiter();
        this.uploadLimiter = runtime.uploadLimiter();
        this.magnetTrackers = magnet.trackers();
        this.metadataExchange = new MetadataExchange(infoHash);
        this.state = SessionState.FETCHING_METADATA;
    }

    /** 磁力連結來源：建立後立即開始背景取 metadata。 */
    static DefaultTorrentSession fromMagnet(MagnetUri magnet, Runtime runtime) {
        DefaultTorrentSession session = new DefaultTorrentSession(magnet, runtime);
        LOG.log(Level.DEBUG, () -> "added magnet " + magnet.infoHash().hex()
                + " (trackers=" + magnet.trackers().size() + ", x.pe=" + magnet.peers().size() + ")");
        session.beginMetadataPhase(magnet);
        return session;
    }

    /** resume 來源：重啟續傳（跳過已完成 piece）。 */
    static DefaultTorrentSession fromResume(ResumeData resume, Runtime runtime) {
        Metainfo meta = resume.metainfo();
        DefaultTorrentSession session = new DefaultTorrentSession(meta, runtime);
        session.restoreState(resume);
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
        startDhtLoop();
    }

    private void restoreState(ResumeData resume) {
        this.plan = new DownloadPlan(resume.saveTo(), resume.selectedFileIndices(), resume.seedAfterComplete());
        this.selection = PieceSelection.of(metainfo, resume.selectedFileIndices());
        // .bt4j 續傳：信任其 bitfield，不重新 recheck 磁碟（快速續傳）
        this.storage = new FileStorage(metainfo, selection, resume.saveTo(), resume.completedPieces());
        this.picker = new RarestFirstPicker(metainfo, selection, storage.completedPieces());
        this.uploadedBytes.set(resume.uploaded());
        recomputeVerifiedBytes();
        Bitfield completed = storage.completedPieces();
        LOG.log(Level.DEBUG, () -> "restore " + infoHash.hex() + ": completed "
                + completed.cardinality() + "/" + metainfo.pieceCount() + " pieces, stopped=" + resume.seedingStopped());

        if (resume.seedingStopped()) {
            setState(SessionState.STOPPED); // 使用者先前已關閉上傳，不啟動網路
        } else if (picker.isComplete()) {
            if (resume.seedAfterComplete()) {
                setState(SessionState.SEEDING);
                launchNetworking();
            } else {
                setState(SessionState.STOPPED); // 已完成且不做種
            }
        } else {
            setState(SessionState.DOWNLOADING);
            launchNetworking();
        }
    }

    private void onMetadataFetched(byte[] infoDictBytes) {
        Metainfo fetched;
        try {
            fetched = Metainfo.fromInfoDict(infoDictBytes, magnetTrackers);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "failed to rebuild metadata from magnet: " + infoHash.hex(), e);
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
        LOG.log(Level.DEBUG, () -> "metadata ready: " + fetched.name() + " (" + fetched.files().size() + " files)");
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
        boolean complete;
        synchronized (lock) {
            if (state != SessionState.METADATA_READY) {
                throw new IllegalStateException("目前狀態 " + state + " 不可 start（變更計畫將於後續支援）");
            }
            this.plan = plan;
            this.selection = PieceSelection.of(metainfo, plan.selectedFileIndices());
            this.storage = new FileStorage(metainfo, selection, plan.saveTo());
            // 掃描目標目錄救回既有半成品（全新下載時為 no-op；.bt4j 續傳走 restore 不經此路）
            try {
                storage.recheck();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "recheck existing files failed, treating as fresh download", e);
            }
            this.picker = new RarestFirstPicker(metainfo, selection, storage.completedPieces());
            recomputeVerifiedBytes();
            setState(SessionState.DOWNLOADING);
            LOG.log(Level.DEBUG, () -> "start download " + metainfo.name() + " -> " + plan.saveTo()
                    + " (selected " + (plan.selectedFileIndices().isEmpty() ? "all" : plan.selectedFileIndices().size())
                    + " files, " + selection.wantedPieceCount() + " pieces)");
            complete = picker.isComplete();
            if (complete) {
                completeDownloadLocked(false); // 目標目錄已有完整資料
            } else {
                launchNetworking();
            }
        }
        if (complete) {
            fireDownloadCompleted();
        }
        // 磁力連結情境：metadata 階段已知的 peer 直接重新排入
        for (PeerAddress peer : knownPeers) {
            peerQueue.offer(peer);
        }
    }

    /** 依已完成的 piece 重算 verifiedWantedBytes。 */
    private void recomputeVerifiedBytes() {
        Bitfield completed = storage.completedPieces();
        long done = 0;
        for (int p = 0; p < metainfo.pieceCount(); p++) {
            if (completed.get(p) && selection.isWanted(p)) {
                done += selection.wantedBytesInPiece(p);
            }
        }
        verifiedWantedBytes.set(done);
    }

    /** 啟動 tracker 排程、DHT 迴圈與（下載中才需要的）peer 連接器。 */
    private void launchNetworking() {
        List<List<Tracker>> tiers = buildTiers();
        if (!tiers.isEmpty()) {
            this.trackerManager = new TrackerManager(tiers, this::request, this::onPeersFound);
            this.trackerManager.start();
        }
        if (state == SessionState.DOWNLOADING) {
            this.connectorThread = Thread.ofVirtual()
                    .name("bt4j-connect-" + infoHash.hex()).start(this::connectorLoop);
        }
        startDhtLoop();
        startPexLoop();
    }

    /** 週期性對支援 ut_pex 的 peer 交換 peer 清單（BEP 11）；private torrent 不啟用。 */
    private void startPexLoop() {
        Metainfo meta = metainfo;
        if (meta != null && meta.isPrivate()) {
            return;
        }
        pexThread = Thread.ofVirtual().name("bt4j-pex-" + infoHash.hex()).start(this::pexLoop);
    }

    private void pexLoop() {
        while (isActive()) {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                return;
            }
            for (PeerWorker worker : workers.values()) {
                if (worker.pex != null) {
                    worker.pex.tick(worker.connection, worker.registry);
                }
            }
        }
    }

    /** 週期性 DHT 找 peer / 宣告；private torrent（BEP 27）不啟用。 */
    private void startDhtLoop() {
        Metainfo meta = metainfo;
        if (dht == null || (meta != null && meta.isPrivate())) {
            return;
        }
        dhtThread = Thread.ofVirtual().name("bt4j-dht-loop-" + infoHash.hex()).start(this::dhtLoop);
    }

    private void dhtLoop() {
        boolean announced = false;
        while (isActive()) {
            if (state == SessionState.FETCHING_METADATA || state == SessionState.DOWNLOADING) {
                try {
                    onPeersFound(dht.findPeers(infoHash).get(60, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    return;
                } catch (ExecutionException | TimeoutException ignored) {
                }
            }
            // 有資料可分享（下載中/做種）就宣告自己是 peer
            if (!announced && storage != null && storage.completedPieces().cardinality() > 0) {
                dht.announce(infoHash, listenPort);
                announced = true;
            }
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private boolean isActive() {
        return state == SessionState.FETCHING_METADATA
                || state == SessionState.DOWNLOADING
                || state == SessionState.SEEDING;
    }

    @Override
    public void stopSeeding() {
        synchronized (lock) {
            if (state == SessionState.STOPPED || state == SessionState.ERROR) {
                return;
            }
            LOG.log(Level.DEBUG, () -> "stop seeding " + infoHash.hex());
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
            long elapsed = now - lastRateTime;
            // 只在取樣間隔到期時重算速率並移動基準；期間內同一輪多個 getter 讀到相同快取值
            if (elapsed >= RATE_SAMPLE_NANOS) {
                double seconds = elapsed / 1e9;
                cachedDownloadRate = (long) ((down - lastDownloaded) / seconds);
                cachedUploadRate = (long) ((up - lastUploaded) / seconds);
                lastRateTime = now;
                lastDownloaded = down;
                lastUploaded = up;
            }
            downRate = cachedDownloadRate;
            upRate = cachedUploadRate;
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
        if (meta == null) {
            throw new IllegalStateException("metadata 尚未就緒，無法產生 resume 資料");
        }
        FileStorage st = storage;
        DownloadPlan p = plan;
        return new ResumeData(meta.toTorrentBytes(),
                st != null ? st.completedPieces() : new Bitfield(meta.pieceCount()),
                p == null ? Set.of() : p.selectedFileIndices(),
                p == null ? null : p.saveTo(),
                uploadedBytes.get(),
                state == SessionState.STOPPED,
                p != null && p.seedAfterComplete());
    }

    @Override
    public java.util.List<FileProgress> fileProgress() {
        Metainfo meta = metainfo;
        PieceSelection sel = selection;
        FileStorage st = storage;
        if (meta == null || sel == null || st == null) {
            return java.util.List.of();
        }
        Bitfield completed = st.completedPieces();
        long pieceLength = meta.pieceLength();
        java.util.List<FileProgress> out = new ArrayList<>();
        for (FileEntry file : meta.files()) {
            if (!sel.isFileWanted(file.index())) {
                continue;
            }
            long downloaded = 0;
            if (file.length() > 0) {
                long fileStart = file.offset();
                long fileEnd = fileStart + file.length();
                int firstPiece = (int) (fileStart / pieceLength);
                int lastPiece = (int) ((fileEnd - 1) / pieceLength);
                for (int piece = firstPiece; piece <= lastPiece; piece++) {
                    if (completed.get(piece)) {
                        long ps = (long) piece * pieceLength;
                        long pe = ps + meta.pieceLengthAt(piece);
                        downloaded += Math.min(fileEnd, pe) - Math.max(fileStart, ps);
                    }
                }
            }
            out.add(new FileProgress(file.index(), file.path(), downloaded, file.length()));
        }
        return out;
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
        LOG.log(Level.DEBUG, () -> "state " + old + " -> " + newState + " (" + infoHash.hex() + ")");
        for (SessionListener listener : listeners) {
            listener.onStateChanged(this, old, newState);
        }
    }

    private void fail(Throwable error) {
        synchronized (lock) {
            if (state == SessionState.STOPPED || state == SessionState.ERROR) {
                return;
            }
            LOG.log(Level.ERROR, "session error: " + infoHash.hex(), error);
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
        Thread d = dhtThread;
        dhtThread = null;
        if (d != null) {
            d.interrupt();
        }
        Thread px = pexThread;
        pexThread = null;
        if (px != null) {
            px.interrupt();
        }
    }

    // ---- tracker announce ----

    private void onPeersFound(List<PeerAddress> peers) {
        for (PeerAddress peer : peers) {
            if (!bannedPeers.contains(peer) && knownPeers.add(peer)) {
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
            if (address == null || workers.size() >= maxPeers || bannedPeers.contains(address)) {
                continue;
            }
            PeerWorker worker = new PeerWorker(address);
            if (workers.putIfAbsent(address, worker) == null) {
                worker.connection.start();
            }
        }
    }

    /** 由 BtClient 的連入 listener 呼叫：接手一條已握手的連入 socket。 */
    void acceptIncoming(Socket socket, Handshake theirHandshake) {
        if ((state != SessionState.DOWNLOADING && state != SessionState.SEEDING) || storage == null) {
            closeQuietly(socket);
            return;
        }
        if (workers.size() >= maxPeers) {
            closeQuietly(socket);
            return;
        }
        InetSocketAddress remote = (InetSocketAddress) socket.getRemoteSocketAddress();
        PeerAddress address = new PeerAddress(remote);
        if (bannedPeers.contains(address)) {
            closeQuietly(socket);
            return;
        }
        PeerWorker worker = new PeerWorker(socket, theirHandshake);
        if (workers.putIfAbsent(address, worker) == null) {
            LOG.log(Level.DEBUG, () -> "accepted incoming peer: " + address);
            worker.connection.start();
        } else {
            closeQuietly(socket);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    /** 單一 peer 連線邏輯。downloadMode 在建立時固定（metadata 階段的連線於轉換時收掉重連）。 */
    private final class PeerWorker implements PeerConnection.Listener {

        final PeerConnection connection;
        final ExtensionRegistry registry;
        final PeerExchange pex; // null = 停用（metadata 階段或 private torrent）
        final Set<BlockRequest> outstanding = ConcurrentHashMap.newKeySet();
        final Set<Integer> allowedFast = ConcurrentHashMap.newKeySet();
        final boolean downloadMode;
        private volatile boolean peerFast;

        PeerWorker(PeerAddress address) {
            Metainfo meta = metainfo;
            this.downloadMode = meta != null;
            this.pex = pexEnabled() ? new PeerExchange(workers::keySet, DefaultTorrentSession.this::onPeersFound) : null;
            this.registry = new ExtensionRegistry(extensions(pex));
            this.connection = PeerConnection.outgoing(
                    address, infoHash, peerId, downloadMode ? meta.pieceCount() : 0, dht != null, this);
        }

        /** 連入連線（一定已知 metadata）。 */
        PeerWorker(Socket socket, Handshake theirHandshake) {
            this.downloadMode = true;
            this.pex = pexEnabled() ? new PeerExchange(workers::keySet, DefaultTorrentSession.this::onPeersFound) : null;
            this.registry = new ExtensionRegistry(extensions(pex));
            this.connection = PeerConnection.incoming(
                    socket, theirHandshake, infoHash, peerId, metainfo.pieceCount(), dht != null, this);
        }

        private List<Extension> extensions(PeerExchange pexOrNull) {
            return pexOrNull == null ? List.of(metadataExchange) : List.of(metadataExchange, pexOrNull);
        }

        @Override
        public void onHandshakeCompleted(PeerConnection conn, Handshake theirs) {
            if (theirs.supportsExtensionProtocol()) {
                conn.send(registry.buildHandshake(metadataExchange.metadataSize().orElse(null)));
            }
            if (dht != null && theirs.supportsDht()) {
                conn.send(new PeerMessage.Port(dht.port())); // 交換 DHT 節點（BEP 5）
            }
            if (downloadMode) {
                this.peerFast = theirs.supportsFastExtension();
                Bitfield have = storage.completedPieces();
                // Fast Extension（BEP 6）：完整/全空時用 HaveAll/HaveNone 取代整個 bitfield
                if (peerFast && have.isComplete()) {
                    conn.send(new PeerMessage.HaveAll());
                } else if (peerFast && have.cardinality() == 0) {
                    conn.send(new PeerMessage.HaveNone());
                } else if (have.cardinality() > 0) {
                    conn.send(new PeerMessage.BitfieldMessage(have));
                }
                if (picker != null && !picker.isComplete()) {
                    conn.send(new PeerMessage.Interested()); // 只有還缺 piece 時才表示興趣
                }
            }
        }

        @Override
        public void onMessage(PeerConnection conn, PeerMessage message) {
            if (message instanceof PeerMessage.Extended extended) {
                registry.dispatch(conn, extended);
                return;
            }
            if (message instanceof PeerMessage.Port(int dhtPort)) {
                if (dht != null && dhtPort > 0) {
                    dht.addNode(new InetSocketAddress(conn.address().socketAddress().getHostString(), dhtPort));
                }
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
                // ---- Fast Extension（BEP 6）----
                case PeerMessage.HaveAll() -> picker.onPeerBitfield(conn.peerBitfield()); // 已由連線設為全滿
                case PeerMessage.RejectRequest(int piece, int begin, int length) -> onReject(piece, begin, length);
                case PeerMessage.AllowedFast(int piece) -> {
                    allowedFast.add(piece);
                    requestAllowedFast(piece);
                }
                // ---- 上傳路徑 ----
                case PeerMessage.Interested() -> {
                    if (canUpload()) {
                        conn.send(new PeerMessage.Unchoke());
                    }
                }
                case PeerMessage.Request(int piece, int begin, int length) -> serveBlock(conn, piece, begin, length);
                default -> {
                    // HaveNone（peerBitfield 已清空）、SuggestPiece 等：忽略
                }
            }
        }

        @Override
        public void onClosed(PeerConnection conn, IOException error) {
            workers.remove(conn.address());
            if (downloadMode && picker != null) {
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

        /** choke 期間仍可向對方請求其宣告的 Allowed Fast piece（BEP 6）。 */
        private void requestAllowedFast(int pieceIndex) {
            if (state != SessionState.DOWNLOADING || !connection.peerBitfield().get(pieceIndex)) {
                return;
            }
            int want = PIPELINE_DEPTH - outstanding.size();
            if (want <= 0) {
                return;
            }
            for (BlockRequest block : picker.pickFromPiece(pieceIndex, want)) {
                if (outstanding.add(block)) {
                    connection.send(new PeerMessage.Request(block.pieceIndex(), block.begin(), block.length()));
                }
            }
        }

        private void onReject(int pieceIndex, int begin, int length) {
            BlockRequest block = new BlockRequest(pieceIndex, begin, length);
            if (outstanding.remove(block)) {
                picker.onRequestsAbandoned(List.of(block)); // 重新排入
                fillPipeline();                              // 立即重試（可能同一 peer 或後續換 peer）
            }
        }

        private void abandonOutstanding() {
            List<BlockRequest> lost = new ArrayList<>(outstanding);
            outstanding.clear();
            picker.onRequestsAbandoned(lost);
        }

        private void onBlock(int pieceIndex, int begin, byte[] data) {
            outstanding.remove(new BlockRequest(pieceIndex, begin, data.length));
            downloadLimiter.acquire(data.length); // 下載限速：延後讀迴圈 → TCP 背壓
            downloadedBytes.addAndGet(data.length);
            // 記錄此 piece 由哪些 peer 貢獻，供驗證失敗時追責
            pieceContributors.computeIfAbsent(pieceIndex, k -> ConcurrentHashMap.newKeySet())
                    .add(connection.address());
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
            Set<PeerAddress> contributors = pieceContributors.remove(pieceIndex);
            if (!valid) {
                // piece SHA-1 失敗：對貢獻過的 peer 記點，累積達門檻即封鎖
                if (contributors != null) {
                    for (PeerAddress contributor : contributors) {
                        strikePeer(contributor);
                    }
                }
                return; // 該 piece 重新排入
            }
            verifiedWantedBytes.addAndGet(selection.wantedBytesInPiece(pieceIndex));
            broadcastHave(pieceIndex);
            fireFileCompletions(pieceIndex);
            if (picker.isComplete()) {
                onDownloadComplete();
            }
        }

        /** 回應對方的 block 請求（上傳）；拒絕時若對方支援 Fast Extension 則明確回 RejectRequest。 */
        private void serveBlock(PeerConnection conn, int pieceIndex, int begin, int length) {
            if (length <= 0 || length > MAX_UPLOAD_BLOCK || begin < 0) {
                LOG.log(Level.WARNING, () -> "rejected malformed block request from " + conn.address()
                        + " piece=" + pieceIndex + " begin=" + begin + " length=" + length);
                reject(conn, pieceIndex, begin, length);
                return;
            }
            boolean haveIt = pieceIndex >= 0 && pieceIndex < metainfo.pieceCount()
                    && storage.completedPieces().get(pieceIndex);
            if (!canUpload() || conn.amChoking() || !haveIt) {
                reject(conn, pieceIndex, begin, length);
                return;
            }
            byte[] data;
            try {
                data = storage.read(pieceIndex, begin, length);
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "failed to read piece " + pieceIndex + " for upload", e);
                reject(conn, pieceIndex, begin, length);
                return;
            }
            uploadLimiter.acquire(length); // 上傳限速
            conn.send(new PeerMessage.Piece(pieceIndex, begin, data));
            uploadedBytes.addAndGet(length);
            LOG.log(Level.TRACE, () -> "uploaded block piece=" + pieceIndex + " begin=" + begin + " -> " + conn.address());
        }

        private void reject(PeerConnection conn, int pieceIndex, int begin, int length) {
            if (peerFast) {
                conn.send(new PeerMessage.RejectRequest(pieceIndex, begin, length));
            }
        }
    }

    private boolean pexEnabled() {
        Metainfo meta = metainfo;
        return meta != null && !meta.isPrivate();
    }

    /** 測試用：目前封鎖的 peer 數。 */
    int bannedPeerCount() {
        return bannedPeers.size();
    }

    /** 記一次驗證失敗給某 peer；累積達門檻即封鎖並斷線。 */
    private void strikePeer(PeerAddress address) {
        int strikes = peerStrikes.merge(address, 1, Integer::sum);
        if (strikes >= MAX_PEER_STRIKES && bannedPeers.add(address)) {
            LOG.log(Level.WARNING, () -> "banning peer " + address + " after " + strikes + " bad pieces");
            PeerWorker worker = workers.get(address);
            if (worker != null) {
                worker.connection.close();
            }
        }
    }

    /** 可上傳條件：有 storage、上傳未被封鎖（uploadRateLimit==0）、狀態為下載中或做種。 */
    private boolean canUpload() {
        return storage != null && !uploadLimiter.isBlocked()
                && (state == SessionState.DOWNLOADING || state == SessionState.SEEDING);
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
                LOG.log(Level.DEBUG, () -> "file completed: " + file.displayPath());
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
            completeDownloadLocked(true);
        }
        fireDownloadCompleted();
    }

    /**
     * 標記下載完成，依 seedAfterComplete 決定做種或停止。呼叫端須持有 lock 且 state==DOWNLOADING。
     *
     * @param networkingUp tracker/DHT/connector 是否已啟動（start-time 完成時為 false）
     */
    private void completeDownloadLocked(boolean networkingUp) {
        boolean seed = plan != null && plan.seedAfterComplete();
        LOG.log(Level.DEBUG, () -> "download complete: " + metainfo.name() + (seed ? " -> seeding" : " -> stopped"));
        if (seed) {
            if (!networkingUp) {
                launchNetworking();
            }
            setState(SessionState.SEEDING);
            Thread c = connectorThread;
            connectorThread = null;
            if (c != null) {
                c.interrupt(); // 做種不再主動連出
            }
            TrackerManager tm = trackerManager;
            if (tm != null) {
                tm.announceCompleted();
            }
        } else {
            setState(SessionState.STOPPED);
            if (networkingUp) {
                teardownPeerMachinery();
            }
        }
    }

    private void fireDownloadCompleted() {
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
