package net.derrek.bt4j;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import net.derrek.bt4j.metainfo.InfoHash;
import net.derrek.bt4j.metainfo.Metainfo;
import net.derrek.bt4j.piece.Bitfield;
import net.derrek.bt4j.session.BtClient;
import net.derrek.bt4j.session.DownloadPlan;
import net.derrek.bt4j.session.SessionListener;
import net.derrek.bt4j.session.SessionState;
import net.derrek.bt4j.session.TorrentSession;
import net.derrek.bt4j.storage.ResumeData;

/**
 * Public entry point (facade) of the BitTorrent engine. A process usually creates a single instance.
 * Internally wraps {@link BtClient}; owns the listen socket, DHT, and global rate limits.
 *
 * Persistence model: each download task has a {@code <info-hash>.bt4j} file named after its
 * info-hash in the target directory (engine-managed). Multiple .bt4j for different torrents
 * can coexist in the same directory.
 * <ul>
 *   <li>{@link #createDownloadJob} creates {@code <info-hash>.bt4j} on the spot (throws if the file for the same torrent already exists).</li>
 *   <li>{@link #restoreDownloadJobs} scans all .bt4j in the directory and returns the job list (empty list if none).</li>
 *   <li>Download complete and not seeding -> delete .bt4j; if seeding -> keep it (can still restore after a restart while seeding).</li>
 *   <li>{@link #stop} hard-stops, keeping the files and .bt4j (can restore again).</li>
 *   <li>{@link #deleteJob} deletes only the .bt4j, keeping already-downloaded files.</li>
 * </ul>
 * {@link #download} deduplicates by info-hash: calling download again for the same torrent returns the same existing task.
 *
 * <pre>{@code
 * try (Bt bt = Bt.builder().listenPort(6881).downloadRateLimit(0).build()) {
 *     TorrentContent content = bt.fromMagnet("magnet:?xt=urn:btih:...", Duration.ofMinutes(3));
 *     List<TorrentContentFile> wanted = content.getFileList().stream()
 *             .filter(f -> f.path().endsWith(".mp4")).toList();
 *     TorrentDownloadJob job = bt.createDownloadJob(wanted, Path.of("/data/movie"), true);
 *     TorrentDownloadTask task = bt.download(job);
 *     // poll task.progress() / task.fileProgress() to update the UI
 *
 *     // after a restart, restore all tasks in the whole directory (download is idempotent, duplicates auto-skipped):
 *     bt.restoreDownloadJobs(Path.of("/data/movie")).forEach(bt::download);
 * }
 * }</pre>
 */
public final class Bt implements AutoCloseable {

    private static final Logger LOG = System.getLogger(Bt.class.getName());
    private static final String BT4J_SUFFIX = ".bt4j";
    private static final Duration PERSIST_INTERVAL = Duration.ofSeconds(5);

    private final BtClient client;
    private final Map<InfoHash, TorrentDownloadTaskImpl> tasks = new ConcurrentHashMap<>();
    private final Map<InfoHash, Long> lastPersistedBytes = new ConcurrentHashMap<>();
    private volatile boolean closed;

    private Bt(Builder builder) {
        this.client = builder.clientBuilder.build();
        Thread.ofVirtual().name("bt4j-facade-persist").start(this::persistLoop);
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---- obtain torrent content ----

    /** Parses a .torrent file and returns the content immediately. */
    public TorrentContent fromTorrent(Path torrentFile) {
        return new TorrentContentImpl(Metainfo.parse(torrentFile));
    }

    public TorrentContent fromTorrent(File torrentFile) {
        return fromTorrent(torrentFile.toPath());
    }

    /**
     * Obtains content from a magnet link. **Blocks** until the metadata is complete or times out
     * (a magnet only has the info-hash; the file list must be requested from the swarm). Once obtained,
     * the temporary session is discarded and holds no resources.
     *
     * @throws TimeoutException still could not be obtained before the timeout (e.g. a dead torrent)
     */
    public TorrentContent fromMagnet(String magnetUri, Duration timeout)
            throws TimeoutException, InterruptedException {
        TorrentSession session = client.addMagnet(magnetUri);
        try {
            Metainfo metainfo = session.awaitMetadata(timeout);
            return new TorrentContentImpl(metainfo);
        } finally {
            client.remove(session.infoHash()); // discard the temporary session used to fetch metadata
        }
    }

    // ---- create/restore tasks ----

    /**
     * Creates a new download task and writes {@code <info-hash>.bt4j} into targetDir on the spot.
     *
     * @param filesToDownload    files to download; **must all come from the same TorrentContent**, otherwise throws. Empty list = all files
     * @param targetDir          target directory (.bt4j for other torrents may coexist in the same directory)
     * @param seedAfterComplete  whether to move into the seeding list after completion
     * @throws IllegalArgumentException filesToDownload mixes files from different torrents
     * @throws IllegalStateException    this torrent's {@code <info-hash>.bt4j} already exists in targetDir
     */
    public TorrentDownloadJob createDownloadJob(List<TorrentContentFile> filesToDownload,
                                                Path targetDir, boolean seedAfterComplete) {
        TorrentContentImpl content = requireSameTorrent(filesToDownload);
        Set<Integer> indices = new HashSet<>();
        for (TorrentContentFile file : filesToDownload) {
            indices.add(file.index());
        }
        Path bt4j = bt4jPath(targetDir, content.infoHashHex());
        if (Files.exists(bt4j)) {
            throw new IllegalStateException("this torrent's .bt4j already exists: " + bt4j);
        }
        TorrentDownloadJobImpl job = new TorrentDownloadJobImpl(
                content, indices, targetDir, seedAfterComplete, false, null);
        // write the initial .bt4j on the spot (completed count 0)
        ResumeData initial = new ResumeData(content.metainfo.toTorrentBytes(),
                new Bitfield(content.metainfo.pieceCount()), Set.copyOf(indices),
                targetDir, 0, false, seedAfterComplete);
        writeResume(bt4j, initial);
        LOG.log(Level.DEBUG, () -> "created job " + content.infoHashHex() + " -> " + targetDir);
        return job;
    }

    public TorrentDownloadJob createDownloadJob(List<TorrentContentFile> filesToDownload,
                                                File targetDir, boolean seedAfterComplete) {
        return createDownloadJob(filesToDownload, targetDir.toPath(), seedAfterComplete);
    }

    /**
     * Scans all {@code <info-hash>.bt4j} in targetDir and restores each into a job (resume after restart).
     * Returns an empty list if the directory has no .bt4j (does not throw). Individual broken/unparseable .bt4j are skipped.
     */
    public List<TorrentDownloadJob> restoreDownloadJobs(Path targetDir) {
        List<TorrentDownloadJob> jobs = new ArrayList<>();
        if (!Files.isDirectory(targetDir)) {
            return jobs;
        }
        try (var stream = Files.newDirectoryStream(targetDir, "*" + BT4J_SUFFIX)) {
            for (Path file : stream) {
                try {
                    ResumeData resume = ResumeData.load(file);
                    TorrentContentImpl content = new TorrentContentImpl(resume.metainfo());
                    jobs.add(new TorrentDownloadJobImpl(content, resume.selectedFileIndices(),
                            targetDir, resume.seedAfterComplete(), true, resume));
                } catch (IOException | RuntimeException e) {
                    LOG.log(Level.WARNING, "skip unreadable .bt4j: " + file, e);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to scan directory: " + targetDir, e);
        }
        return jobs;
    }

    public List<TorrentDownloadJob> restoreDownloadJobs(File targetDir) {
        return restoreDownloadJobs(targetDir.toPath());
    }

    // ---- run and query ----

    /** Starts running the task and returns a handle for polling progress. Repeated calls for the same torrent return the existing task. */
    public TorrentDownloadTask download(TorrentDownloadJob job) {
        TorrentDownloadJobImpl impl = (TorrentDownloadJobImpl) job;
        InfoHash infoHash = impl.content.metainfo.infoHash();
        TorrentDownloadTaskImpl existing = tasks.get(infoHash);
        if (existing != null) {
            return existing; // idempotent: already running
        }
        Path bt4j = bt4jPath(impl.targetDir, impl.content.infoHashHex());
        TorrentSession session;
        if (impl.fromRestore) {
            session = client.restore(impl.resumeData); // trust the .bt4j bitfield, fast resume
        } else {
            session = client.addTorrent(impl.content.metainfo);
            session.start(new DownloadPlan(impl.targetDir, impl.selectedIndices, impl.seedAfter)); // includes recheck
        }
        TorrentDownloadTaskImpl task = new TorrentDownloadTaskImpl(session, impl.content, bt4j, impl.seedAfter);
        tasks.put(infoHash, task);
        session.addListener(new CompletionListener(task));
        LOG.log(Level.DEBUG, () -> "download started " + impl.content.infoHashHex());
        return task;
    }

    /**
     * Changes which files a running task downloads. Newly selected files start downloading (a completed, seeding
     * task returns to downloading); deselected files stop and their partial data is left on disk. The target
     * directory is unchanged. {@code filesToDownload} must all come from this task's torrent; an empty list means
     * all files. The {@code .bt4j} sidecar is rewritten to reflect the new selection.
     *
     * @throws IllegalArgumentException the files come from a different torrent, or the list is empty
     */
    public void changeSelection(TorrentDownloadTask task, List<TorrentContentFile> filesToDownload) {
        TorrentDownloadTaskImpl impl = (TorrentDownloadTaskImpl) task;
        TorrentContentImpl content = requireSameTorrent(filesToDownload);
        if (content != impl.content) {
            throw new IllegalArgumentException("filesToDownload belong to a different torrent than the task");
        }
        Set<Integer> indices = new HashSet<>();
        for (TorrentContentFile file : filesToDownload) {
            indices.add(file.index());
        }
        impl.session.start(new DownloadPlan(impl.targetDirectory(), indices, impl.seedAfter));
        persist(impl); // record the new selection in .bt4j immediately
        LOG.log(Level.DEBUG, () -> "changed selection for " + impl.content.infoHashHex() + " -> " + indices.size() + " files");
    }

    /** Tasks currently downloading. */
    public List<TorrentDownloadTask> getDownloadTaskList() {
        return tasks.values().stream()
                .filter(t -> t.sessionState() == SessionState.DOWNLOADING)
                .map(t -> (TorrentDownloadTask) t)
                .toList();
    }

    /** Tasks currently seeding. */
    public List<TorrentDownloadTask> getSeedingTaskList() {
        return tasks.values().stream()
                .filter(t -> t.sessionState() == SessionState.SEEDING)
                .map(t -> (TorrentDownloadTask) t)
                .toList();
    }

    // ---- lifecycle control ----

    /** Hard-stops the task (not a pause). Keeps downloaded files and .bt4j; can later {@link #restoreDownloadJobs} to resume. */
    public void stop(TorrentDownloadTask task) {
        TorrentDownloadTaskImpl impl = (TorrentDownloadTaskImpl) task;
        InfoHash infoHash = impl.content.metainfo.infoHash();
        persist(impl); // write the latest progress to .bt4j before stopping
        tasks.remove(infoHash);
        lastPersistedBytes.remove(infoHash);
        client.remove(infoHash);
        LOG.log(Level.DEBUG, () -> "stopped " + impl.content.infoHashHex());
    }

    /** Stops the task and deletes its .bt4j file (keeps the already-downloaded data files). */
    public void deleteJob(TorrentDownloadTask task) {
        TorrentDownloadTaskImpl impl = (TorrentDownloadTaskImpl) task;
        InfoHash infoHash = impl.content.metainfo.infoHash();
        tasks.remove(infoHash);
        lastPersistedBytes.remove(infoHash);
        client.remove(infoHash);
        deleteQuietly(impl.bt4jPath);
        LOG.log(Level.DEBUG, () -> "deleted job " + impl.content.infoHashHex());
    }

    /** Closes the engine: stops all tasks, DHT, and the listen socket. Downloaded files and .bt4j are kept. */
    @Override
    public void close() {
        closed = true;
        for (TorrentDownloadTaskImpl task : tasks.values()) {
            persist(task); // save progress before closing
        }
        tasks.clear();
        client.close();
    }

    // ---- completion event: keep or delete .bt4j depending on seedAfter ----

    private final class CompletionListener implements SessionListener {
        private final TorrentDownloadTaskImpl task;

        CompletionListener(TorrentDownloadTaskImpl task) {
            this.task = task;
        }

        @Override
        public void onDownloadCompleted(TorrentSession session) {
            if (task.seedAfter) {
                persist(task); // seeding: update .bt4j to the completed state and keep it
            } else {
                InfoHash infoHash = session.infoHash();
                tasks.remove(infoHash);
                lastPersistedBytes.remove(infoHash);
                deleteQuietly(task.bt4jPath); // completed and not seeding: delete .bt4j
                client.remove(infoHash);
                LOG.log(Level.DEBUG, () -> "completed (no seed), removed .bt4j: " + infoHash.hex());
            }
        }
    }

    // ---- periodic persistence ----

    private void persistLoop() {
        while (!closed) {
            try {
                Thread.sleep(PERSIST_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                return;
            }
            for (TorrentDownloadTaskImpl task : tasks.values()) {
                SessionState state = task.sessionState();
                if (state == SessionState.DOWNLOADING || state == SessionState.SEEDING) {
                    persist(task);
                }
            }
        }
    }

    /** Atomically writes the task's .bt4j if progress has changed. */
    private void persist(TorrentDownloadTaskImpl task) {
        if (!tasks.containsKey(task.content.metainfo.infoHash())) {
            return; // already removed (e.g. just completed and deleted), avoid recreating
        }
        ResumeData resume;
        try {
            resume = task.session.resumeData();
        } catch (RuntimeException e) {
            return; // metadata not ready, etc.
        }
        InfoHash infoHash = task.content.metainfo.infoHash();
        long bytes = resume.completedPieces().cardinality();
        Long last = lastPersistedBytes.get(infoHash);
        if (last != null && last == bytes) {
            return; // no change
        }
        writeResume(task.bt4jPath, resume);
        lastPersistedBytes.put(infoHash, bytes);
    }

    // ---- utilities ----

    private static Path bt4jPath(Path targetDir, String infoHashHex) {
        return targetDir.resolve(infoHashHex + BT4J_SUFFIX);
    }

    /** Atomic write: write .tmp first then rename, to avoid a corrupt file if a crash happens mid-write. */
    private static void writeResume(Path bt4j, ResumeData resume) {
        try {
            Files.createDirectories(bt4j.getParent());
            Path tmp = bt4j.resolveSibling(bt4j.getFileName() + ".tmp");
            Files.write(tmp, resume.encode());
            try {
                Files.move(tmp, bt4j, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, bt4j, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "failed to persist .bt4j: " + bt4j, e);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "failed to delete .bt4j: " + file, e);
        }
    }

    private static TorrentContentImpl requireSameTorrent(List<TorrentContentFile> files) {
        if (files.isEmpty()) {
            throw new IllegalArgumentException("filesToDownload must not be empty (to select all, pass the entire getFileList())");
        }
        TorrentContent content = files.getFirst().content();
        for (TorrentContentFile file : files) {
            if (file.content() != content) {
                throw new IllegalArgumentException("filesToDownload contains files from different torrents; mixing is not allowed");
            }
        }
        return (TorrentContentImpl) content;
    }

    /** Engine settings. */
    public static final class Builder {

        private final BtClient.Builder clientBuilder = BtClient.builder();

        private Builder() {
        }

        /** TCP listen port (also used as the DHT UDP port). 0 = system-assigned. Default 6881. */
        public Builder listenPort(int port) {
            clientBuilder.listenPort(port);
            return this;
        }

        /** Disables DHT (enabled by default). */
        public Builder dhtEnabled(boolean enabled) {
            clientBuilder.dhtEnabled(enabled);
            return this;
        }

        /** Enables the uTP transport (BEP 29) alongside TCP for peer connections. Disabled by default. */
        public Builder utpEnabled(boolean enabled) {
            clientBuilder.utpEnabled(enabled);
            return this;
        }

        /** Enables Local Service Discovery (BEP 14) for LAN peers. Disabled by default (multicasts on the LAN). */
        public Builder lsdEnabled(boolean enabled) {
            clientBuilder.lsdEnabled(enabled);
            return this;
        }

        /** Enables automatic NAT port mapping (NAT-PMP with a UPnP fallback). Disabled by default. */
        public Builder portMappingEnabled(boolean enabled) {
            clientBuilder.portMappingEnabled(enabled);
            return this;
        }

        /** Global download rate limit (bytes/s). {@code <= 0} unlimited; {@code > 0} rate-limited. */
        public Builder downloadRateLimit(long bytesPerSec) {
            clientBuilder.downloadRateLimit(bytesPerSec);
            return this;
        }

        /**
         * Global upload rate limit (bytes/s).
         * {@code == 0} no uploading at all (keeps peers choked and rejects requests during download/seeding);
         * {@code < 0} unlimited; {@code > 0} rate-limited.
         */
        public Builder uploadRateLimit(long bytesPerSec) {
            clientBuilder.uploadRateLimit(bytesPerSec);
            return this;
        }

        /** Maximum peer connections per torrent, default 30. */
        public Builder maxPeersPerTorrent(int max) {
            clientBuilder.maxPeersPerTorrent(max);
            return this;
        }

        public Bt build() {
            return new Bt(this);
        }
    }
}
