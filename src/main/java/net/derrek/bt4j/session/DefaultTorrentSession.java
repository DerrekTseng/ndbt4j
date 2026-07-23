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
 * The default implementation of TorrentSession.
 * The source can be a .torrent (directly METADATA_READY), a magnet link (FETCHING_METADATA → fetch metadata via BEP 10/9),
 * or resume data (resumed after a restart, skipping completed pieces).
 * After the download completes it enters SEEDING and responds to others' Requests (uploading); stopSeeding manually stops uploading.
 */
final class DefaultTorrentSession implements TorrentSession {

    private static final Logger LOG = System.getLogger(DefaultTorrentSession.class.getName());

    /** Adaptive pipeline depth: floor (also the depth before we have a rate sample) and ceiling. */
    private static final int MIN_PIPELINE = 16;
    private static final int MAX_PIPELINE = 256;
    /**
     * Pipeline depth targets the bandwidth-delay product: keep {@value #RTT_HEADROOM} round-trips of data in
     * flight so the pipe never drains while waiting for the next block. Depth = rate x (RTT x headroom) / block.
     */
    private static final double RTT_HEADROOM = 2.5;
    /** RTT estimate used before a peer has produced a measurement. */
    private static final long DEFAULT_RTT_NANOS = 200_000_000L; // 200ms
    /** Cap on the in-flight window (seconds), so a pathological RTT cannot explode the pipeline. */
    private static final double MAX_PIPELINE_WINDOW_SECONDS = 4.0;
    /** Maximum length of a single uploaded block (guards against malicious Requests for oversized blocks). */
    private static final int MAX_UPLOAD_BLOCK = 128 * 1024;
    /** Number of upload slots: peers unchoked at once (including 1 optimistic slot). */
    private static final int UPLOAD_SLOTS = 4;
    /** The choke re-evaluation interval. */
    private static final long CHOKE_INTERVAL_MILLIS = 10_000;
    /** How many choke rounds between each optimistic-unchoke rotation (10s × 3 = 30s). */
    private static final int OPTIMISTIC_EVERY_ROUNDS = 3;
    /** Anti-snubbing: a peer with outstanding requests that sends no block for this long is considered to be snubbing us. */
    private static final long SNUB_TIMEOUT_NANOS = 60_000_000_000L;

    private final InfoHash infoHash;
    private final PeerId peerId;
    private final int listenPort;
    private final int maxPeers;
    private final DhtClient dht; // null = disabled
    private final net.derrek.bt4j.util.RateLimiter downloadLimiter;
    private final net.derrek.bt4j.util.RateLimiter uploadLimiter;
    private final List<java.net.URI> magnetTrackers;
    private final MetadataExchange metadataExchange;
    private final CompletableFuture<Metainfo> metadataFuture = new CompletableFuture<>();

    /** Ban a peer once it has caused this many consecutive piece verification failures. */
    private static final int MAX_PEER_STRIKES = 3;

    private final List<SessionListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<PeerAddress, PeerWorker> workers = new ConcurrentHashMap<>();
    private final Set<PeerAddress> knownPeers = ConcurrentHashMap.newKeySet();
    private final BlockingQueue<PeerAddress> peerQueue = new LinkedBlockingQueue<>();
    private final Set<Integer> completedFileEvents = ConcurrentHashMap.newKeySet();
    private final AtomicLong downloadedBytes = new AtomicLong();
    private final AtomicLong uploadedBytes = new AtomicLong();
    private final AtomicLong verifiedWantedBytes = new AtomicLong();

    // Bad-peer tracking: piece → peers that contributed blocks; each peer's verification-failure count; the ban list
    private final Map<Integer, Set<PeerAddress>> pieceContributors = new ConcurrentHashMap<>();
    private final Map<PeerAddress, Integer> peerStrikes = new ConcurrentHashMap<>();
    private final Set<PeerAddress> bannedPeers = ConcurrentHashMap.newKeySet();

    private final Object lock = new Object();
    private final Object listenerLock = new Object(); // guards the atomicity of "adding a listener" and "replaying the metadata event"
    private volatile SessionState state;
    private volatile Metainfo metainfo; // magnet-link source: null until metadata is obtained
    private volatile DownloadPlan plan;
    private volatile PieceSelection selection;
    private volatile FileStorage storage;
    private volatile RarestFirstPicker picker;
    private volatile TrackerManager trackerManager;
    private volatile Thread connectorThread;
    private volatile Thread dhtThread;
    private volatile Thread pexThread;
    private volatile Thread chokeThread;

    // choke algorithm state (guarded by chokeLock)
    private final Object chokeLock = new Object();
    private volatile PeerWorker optimisticPeer;
    private int chokeRoundCounter;
    // Timing (instance fields so tests can shorten them); default to the constants above.
    private volatile long chokeIntervalMillis = CHOKE_INTERVAL_MILLIS;
    private volatile long snubTimeoutNanos = SNUB_TIMEOUT_NANOS;

    // Rate calculation: re-sample the delta only every ≥0.5 seconds, so that multiple getters in the same round
    // calling stats() don't each reset the baseline within a few-microsecond window and compute 0 or noisy rates.
    private static final long RATE_SAMPLE_NANOS = 500_000_000L;
    private long lastRateTime = System.nanoTime();
    private long lastDownloaded;
    private long lastUploaded;
    private long cachedDownloadRate;
    private long cachedUploadRate;

    /** The shared runtime environment at the BtClient level (peer id, listen port, DHT, rate limiters, etc.). */
    record Runtime(PeerId peerId, int listenPort, int maxPeers, DhtClient dht,
                   net.derrek.bt4j.util.RateLimiter downloadLimiter,
                   net.derrek.bt4j.util.RateLimiter uploadLimiter) {
    }

    /** .torrent source. */
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
        this.metadataExchange.supply(metainfo.infoDictBytes()); // can respond to others' metadata requests
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

    /** Magnet-link source: begins fetching metadata in the background immediately after creation. */
    static DefaultTorrentSession fromMagnet(MagnetUri magnet, Runtime runtime) {
        DefaultTorrentSession session = new DefaultTorrentSession(magnet, runtime);
        LOG.log(Level.DEBUG, () -> "added magnet " + magnet.infoHash().hex()
                + " (trackers=" + magnet.trackers().size() + ", x.pe=" + magnet.peers().size() + ")");
        session.beginMetadataPhase(magnet);
        return session;
    }

    /** Resume source: resumes after a restart (skipping completed pieces). */
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
        // .bt4j resume: trust its bitfield, don't re-recheck the disk (fast resume)
        this.storage = new FileStorage(metainfo, selection, resume.saveTo(), resume.completedPieces());
        this.picker = new RarestFirstPicker(metainfo, selection, storage.completedPieces());
        this.uploadedBytes.set(resume.uploaded());
        recomputeVerifiedBytes();
        Bitfield completed = storage.completedPieces();
        LOG.log(Level.DEBUG, () -> "restore " + infoHash.hex() + ": completed "
                + completed.cardinality() + "/" + metainfo.pieceCount() + " pieces, stopped=" + resume.seedingStopped());

        if (resume.seedingStopped()) {
            setState(SessionState.STOPPED); // the user previously stopped uploading; don't start networking
        } else if (picker.isComplete()) {
            if (resume.seedAfterComplete()) {
                setState(SessionState.SEEDING);
                launchNetworking();
            } else {
                setState(SessionState.STOPPED); // already complete and not seeding
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
            teardownPeerMachinery(); // tear down the metadata-stage connections; the download stage reconnects with the correct pieceCount
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
            throw new IllegalStateException("failed to obtain metadata", e.getCause());
        } catch (CancellationException e) {
            throw new IllegalStateException("session already closed", e);
        }
    }

    @Override
    public void start(DownloadPlan plan) {
        boolean complete;
        synchronized (lock) {
            if (state != SessionState.METADATA_READY) {
                throw new IllegalStateException("cannot start in current state " + state + " (changing the plan will be supported later)");
            }
            this.plan = plan;
            this.selection = PieceSelection.of(metainfo, plan.selectedFileIndices());
            this.storage = new FileStorage(metainfo, selection, plan.saveTo());
            // Scan the target directory to recover an existing partial download (a no-op for a fresh download; .bt4j resume goes through restore and does not take this path)
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
                completeDownloadLocked(false); // the target directory already holds the complete data
            } else {
                launchNetworking();
            }
        }
        if (complete) {
            fireDownloadCompleted();
        }
        // Magnet-link scenario: re-enqueue the peers already known from the metadata stage
        for (PeerAddress peer : knownPeers) {
            peerQueue.offer(peer);
        }
    }

    /** Recomputes verifiedWantedBytes from the completed pieces. */
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

    /** Starts the tracker scheduler, the DHT loop, and (only needed while downloading) the peer connector. */
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
        chokeThread = Thread.ofVirtual().name("bt4j-choke-" + infoHash.hex()).start(this::chokeLoop);
    }

    // ---- choke algorithm (BEP 3 recommendation + optimistic unchoke) ----

    private void chokeLoop() {
        while (isActive()) {
            try {
                Thread.sleep(chokeIntervalMillis);
            } catch (InterruptedException e) {
                return;
            }
            runChokeRound(true);
            if (state == SessionState.DOWNLOADING) {
                List<PeerWorker> snubbed = checkSnubbing();
                topUpPipelines(snubbed);
            }
        }
    }

    /**
     * Anti-snubbing: a peer that accepted our requests but has sent no block for {@value #SNUB_TIMEOUT_NANOS}ns
     * is "snubbing" us. Abandon its outstanding requests so the picker re-hands those blocks to responsive peers,
     * instead of waiting out the full read timeout. Returns the snubbed workers.
     */
    private List<PeerWorker> checkSnubbing() {
        long now = System.nanoTime();
        List<PeerWorker> snubbed = new ArrayList<>();
        for (PeerWorker worker : workers.values()) {
            if (worker.downloadMode && !worker.outstanding.isEmpty()
                    && now - worker.lastBlockNanos > snubTimeoutNanos) {
                snubbed.add(worker);
            }
        }
        for (PeerWorker worker : snubbed) {
            LOG.log(Level.DEBUG, () -> "peer " + worker.connection.address() + " snubbed us; re-distributing its blocks");
            worker.abandonOutstanding();
        }
        return snubbed;
    }

    /** Keep pipelines full: request from every unchoked download peer with spare capacity (skips the just-snubbed ones this round). */
    private void topUpPipelines(List<PeerWorker> skip) {
        for (PeerWorker worker : workers.values()) {
            if (worker.downloadMode && !skip.contains(worker)) {
                worker.fillPipeline();
            }
        }
    }

    /**
     * Re-decides which peers to unchoke.
     * There are a fixed {@value #UPLOAD_SLOTS} upload slots: the first few go to the most contributing peers by "recent rate" (tit-for-tat),
     * with 1 reserved optimistic slot rotated randomly every {@value #OPTIMISTIC_EVERY_ROUNDS} rounds (to discover new peers and bootstrap reciprocity).
     *
     * @param periodic true = periodic call (re-sample rates + rotate optimistic); false = an immediate re-evaluation on an interest change
     */
    private void runChokeRound(boolean periodic) {
        synchronized (chokeLock) {
            if (!canUpload()) {
                for (PeerWorker worker : workers.values()) {
                    if (!worker.connection.amChoking()) {
                        worker.connection.send(new PeerMessage.Choke());
                    }
                }
                return;
            }
            boolean seeding = state == SessionState.SEEDING;
            List<PeerWorker> interested = new ArrayList<>();
            for (PeerWorker worker : workers.values()) {
                if (worker.downloadMode && worker.connection.peerInterested()) {
                    interested.add(worker);
                }
            }

            if (periodic) {
                // Use this round's byte delta as the "recent rate" for ranking
                for (PeerWorker worker : workers.values()) {
                    long current = seeding ? worker.bytesToPeer.get() : worker.bytesFromPeer.get();
                    worker.recentRate = current - worker.lastRoundBytes;
                    worker.lastRoundBytes = current;
                }
                if (chokeRoundCounter++ % OPTIMISTIC_EVERY_ROUNDS == 0) {
                    optimisticPeer = interested.isEmpty() ? null
                            : interested.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(interested.size()));
                }
            }

            // Sort by recent rate descending; on a tie, prefer peers already unchoked (hysteresis, to avoid flapping)
            interested.sort((a, b) -> {
                int byRate = Long.compare(b.recentRate, a.recentRate);
                if (byRate != 0) {
                    return byRate;
                }
                boolean aUnchoked = !a.connection.amChoking();
                boolean bUnchoked = !b.connection.amChoking();
                return aUnchoked == bUnchoked ? 0 : (aUnchoked ? -1 : 1);
            });
            java.util.Set<PeerWorker> unchoke = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            int regularSlots = Math.max(1, UPLOAD_SLOTS - 1);
            for (PeerWorker worker : interested) {
                if (unchoke.size() >= regularSlots) {
                    break;
                }
                unchoke.add(worker);
            }
            PeerWorker optimistic = optimisticPeer;
            if (optimistic != null && interested.contains(optimistic)) {
                unchoke.add(optimistic); // the optimistic slot (may bring the total up to UPLOAD_SLOTS)
            }

            for (PeerWorker worker : workers.values()) {
                boolean shouldUnchoke = unchoke.contains(worker);
                if (shouldUnchoke && worker.connection.amChoking()) {
                    worker.connection.send(new PeerMessage.Unchoke());
                } else if (!shouldUnchoke && !worker.connection.amChoking()) {
                    worker.connection.send(new PeerMessage.Choke());
                }
            }
        }
    }

    /** Periodically exchanges peer lists with peers that support ut_pex (BEP 11); not enabled for private torrents. */
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

    /** Periodically finds peers via DHT and announces; not enabled for private torrents (BEP 27). */
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
            // Once there's data to share (downloading/seeding), announce ourselves as a peer
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
            // Only recompute the rate and move the baseline when the sampling interval has elapsed; within it, getters in the same round read the same cached values
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
     * Registers an event listener. If metadata is already ready, onMetadataReady is immediately replayed to the new listener
     * (avoids missing the event when metadata was fetched "after addMagnet but before addListener" — a common ordering in caller UIs).
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
            throw new IllegalStateException("metadata not yet ready, cannot produce resume data");
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

    // ---- state and events ----

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

    /** Tears down connections, the tracker scheduler, and the connector (leaves the state unchanged, up to the caller). */
    private void teardownPeerMachinery() {
        for (PeerWorker worker : workers.values()) {
            worker.connection.close();
        }
        workers.clear();
        TrackerManager tm = trackerManager;
        trackerManager = null;
        if (tm != null) {
            tm.close(); // makes a best effort to send stopped on the scheduler thread
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
        Thread ch = chokeThread;
        chokeThread = null;
        if (ch != null) {
            ch.interrupt();
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
                // unsupported scheme (ws, etc.)
            }
        }
        return trackers;
    }

    /** BEP 12 tier structure (order within a tier is managed by TrackerManager via shuffling and promotion). */
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
                : 1; // metadata unknown: report 1 to indicate not yet complete
        return new AnnounceRequest(infoHash, peerId, listenPort,
                uploadedBytes.get(), downloadedBytes.get(), left, event, 50);
    }

    // ---- peer connection management ----

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

    /** Called by BtClient's incoming listener: takes over an already-handshaked incoming socket. */
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

    /** The logic for a single peer connection. downloadMode is fixed at creation (metadata-stage connections are torn down and reconnected on transition). */
    private final class PeerWorker implements PeerConnection.Listener {

        final PeerConnection connection;
        final ExtensionRegistry registry;
        final PeerExchange pex; // null = disabled (metadata stage or private torrent)
        final Set<BlockRequest> outstanding = ConcurrentHashMap.newKeySet();
        final Set<Integer> allowedFast = ConcurrentHashMap.newKeySet();
        final boolean downloadMode;
        private volatile boolean peerFast;
        // per-peer statistics used by the choke algorithm
        final AtomicLong bytesFromPeer = new AtomicLong(); // bytes downloaded from this peer (leech ranking key)
        final AtomicLong bytesToPeer = new AtomicLong();   // bytes uploaded to this peer (seed ranking key)
        volatile long lastRoundBytes;                       // the baseline from the previous choke round
        volatile long recentRate;                           // this round's delta (for ranking / pipeline depth)
        volatile long lastBlockNanos = System.nanoTime();   // last time a block arrived (anti-snubbing)
        // RTT estimate for bandwidth-delay-product pipeline sizing: sample the latency of a block requested
        // when the pipeline was empty, tracked as a decaying minimum (the noise floor ~= true RTT).
        volatile long rttNanos = DEFAULT_RTT_NANOS;
        private volatile long rttSampleSentNanos;
        private volatile boolean awaitingRttSample;

        /** Adaptive pipeline depth for this peer, scaled to its recent download rate (bounded). */
        private int desiredPipeline() {
            long ratePerSec = recentRate / Math.max(1, chokeIntervalMillis / 1000);
            return pipelineDepth(ratePerSec, rttNanos);
        }

        PeerWorker(PeerAddress address) {
            Metainfo meta = metainfo;
            this.downloadMode = meta != null;
            this.pex = pexEnabled() ? new PeerExchange(workers::keySet, DefaultTorrentSession.this::onPeersFound) : null;
            this.registry = new ExtensionRegistry(extensions(pex));
            this.connection = PeerConnection.outgoing(
                    address, infoHash, peerId, downloadMode ? meta.pieceCount() : 0, dht != null, this);
        }

        /** Incoming connection (metadata is always known). */
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
                conn.send(new PeerMessage.Port(dht.port())); // exchange DHT nodes (BEP 5)
            }
            if (downloadMode) {
                this.peerFast = theirs.supportsFastExtension();
                Bitfield have = storage.completedPieces();
                // Fast Extension (BEP 6): use HaveAll/HaveNone instead of the full bitfield when complete/empty
                if (peerFast && have.isComplete()) {
                    conn.send(new PeerMessage.HaveAll());
                } else if (peerFast && have.cardinality() == 0) {
                    conn.send(new PeerMessage.HaveNone());
                } else if (have.cardinality() > 0) {
                    conn.send(new PeerMessage.BitfieldMessage(have));
                }
                if (picker != null && !picker.isComplete()) {
                    conn.send(new PeerMessage.Interested()); // only express interest when we still need pieces
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
                return; // the metadata stage only cares about extension messages
            }
            switch (message) {
                case PeerMessage.Unchoke() -> fillPipeline();
                case PeerMessage.Choke() -> abandonOutstanding();
                case PeerMessage.BitfieldMessage(Bitfield bf) -> picker.onPeerBitfield(bf);
                case PeerMessage.Have(int piece) -> picker.onPeerHave(piece);
                case PeerMessage.Piece(int piece, int begin, byte[] data) -> onBlock(piece, begin, data);
                // ---- Fast Extension (BEP 6) ----
                case PeerMessage.HaveAll() -> picker.onPeerBitfield(conn.peerBitfield()); // already set to full by the connection
                case PeerMessage.RejectRequest(int piece, int begin, int length) -> onReject(piece, begin, length);
                case PeerMessage.AllowedFast(int piece) -> {
                    allowedFast.add(piece);
                    requestAllowedFast(piece);
                }
                // ---- upload path: the choke algorithm decides whether to unchoke ----
                case PeerMessage.Interested() -> runChokeRound(false);      // immediate re-evaluation on interest change
                case PeerMessage.NotInterested() -> runChokeRound(false);   // free up a slot for other peers
                case PeerMessage.Request(int piece, int begin, int length) -> serveBlock(conn, piece, begin, length);
                default -> {
                    // HaveNone (peerBitfield already cleared), SuggestPiece, etc.: ignore
                }
            }
        }

        @Override
        public void onClosed(PeerConnection conn, IOException error) {
            workers.remove(conn.address());
            if (optimisticPeer == this) {
                optimisticPeer = null;
            }
            if (downloadMode && picker != null) {
                abandonOutstanding();
                picker.onPeerGone(conn.peerBitfield());
                runChokeRound(false); // reallocate the freed upload slot
            }
        }

        private void fillPipeline() {
            if (connection.peerChoking() || state != SessionState.DOWNLOADING) {
                return;
            }
            // Requesting from an empty pipeline: the next block's latency is a clean RTT sample (no queueing).
            if (outstanding.isEmpty()) {
                rttSampleSentNanos = System.nanoTime();
                awaitingRttSample = true;
            }
            int want = desiredPipeline() - outstanding.size();
            if (want <= 0) {
                return;
            }
            for (BlockRequest block : picker.pick(connection.peerBitfield(), want)) {
                if (outstanding.add(block)) {
                    connection.send(new PeerMessage.Request(block.pieceIndex(), block.begin(), block.length()));
                }
            }
        }

        /** Even while choked, we can still request the peer's advertised Allowed Fast pieces (BEP 6). */
        private void requestAllowedFast(int pieceIndex) {
            if (state != SessionState.DOWNLOADING || !connection.peerBitfield().get(pieceIndex)) {
                return;
            }
            int want = desiredPipeline() - outstanding.size();
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
                picker.onRequestsAbandoned(List.of(block)); // re-enqueue
                fillPipeline();                              // retry immediately (possibly the same peer, or a different one later)
            }
        }

        private void abandonOutstanding() {
            List<BlockRequest> lost = new ArrayList<>(outstanding);
            outstanding.clear();
            picker.onRequestsAbandoned(lost);
        }

        private void onBlock(int pieceIndex, int begin, byte[] data) {
            outstanding.remove(new BlockRequest(pieceIndex, begin, data.length));
            downloadLimiter.acquire(data.length); // download throttling: stalls the read loop → TCP backpressure
            downloadedBytes.addAndGet(data.length);
            bytesFromPeer.addAndGet(data.length); // choke algorithm: tit-for-tat ranking key
            long now = System.nanoTime();
            lastBlockNanos = now;                 // anti-snubbing: this peer is delivering
            // RTT sample: the block requested from an empty pipeline just arrived. Track a decaying minimum
            // (new minima adopted immediately; the estimate drifts up slowly if the true RTT rises) to avoid
            // the positive-feedback loop that using average latency would create with a deep pipeline.
            if (awaitingRttSample) {
                awaitingRttSample = false;
                long sample = now - rttSampleSentNanos;
                if (sample > 0) {
                    rttNanos = sample < rttNanos ? sample : rttNanos + (sample - rttNanos) / 32;
                }
            }
            // Record which peers contributed to this piece, for accountability on verification failure
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
                // piece SHA-1 failed: strike the contributing peers, banning them once they hit the threshold
                if (contributors != null) {
                    for (PeerAddress contributor : contributors) {
                        strikePeer(contributor);
                    }
                }
                return; // the piece is re-enqueued
            }
            verifiedWantedBytes.addAndGet(selection.wantedBytesInPiece(pieceIndex));
            broadcastHave(pieceIndex);
            fireFileCompletions(pieceIndex);
            if (picker.isComplete()) {
                onDownloadComplete();
            }
        }

        /** Responds to the peer's block request (uploading); on rejection, if the peer supports the Fast Extension, explicitly reply with RejectRequest. */
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
            uploadLimiter.acquire(length); // upload throttling
            conn.send(new PeerMessage.Piece(pieceIndex, begin, data));
            uploadedBytes.addAndGet(length);
            bytesToPeer.addAndGet(length); // choke algorithm: the ranking key while seeding
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

    /** For testing: the current number of banned peers. */
    int bannedPeerCount() {
        return bannedPeers.size();
    }

    /** For testing: shorten the choke-round interval and snub timeout so anti-snubbing can be exercised quickly. */
    void setChokeTimingForTest(long chokeIntervalMillis, long snubTimeoutNanos) {
        this.chokeIntervalMillis = chokeIntervalMillis;
        this.snubTimeoutNanos = snubTimeoutNanos;
    }

    /**
     * Pipeline depth for a peer from its download rate and RTT (bandwidth-delay product).
     * In-flight window = min(cap, RTT x headroom); blocks = rate x window / blockSize, clamped to [MIN, MAX].
     * Package-private static so it is unit-testable.
     */
    static int pipelineDepth(long ratePerSec, long rttNanos) {
        double windowSeconds = Math.min(MAX_PIPELINE_WINDOW_SECONDS, rttNanos / 1e9 * RTT_HEADROOM);
        long blocks = (long) (ratePerSec * windowSeconds / BlockRequest.BLOCK_SIZE);
        return (int) Math.max(MIN_PIPELINE, Math.min(MAX_PIPELINE, blocks));
    }

    /** Records a verification failure against a peer; bans and disconnects it once the threshold is reached. */
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

    /** Conditions for being able to upload: storage exists, uploading is not blocked (uploadRateLimit==0), and the state is downloading or seeding. */
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
     * Marks the download complete, deciding whether to seed or stop based on seedAfterComplete. The caller must hold lock and have state==DOWNLOADING.
     *
     * @param networkingUp whether tracker/DHT/connector are already started (false when completing at start time)
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
                c.interrupt(); // seeding no longer dials out actively
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
