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
 * BitTorrent 引擎對外入口（facade）。一個程序通常建立一個實例。
 * 內部包裝 {@link BtClient}；持有 listen socket、DHT、全域限速。
 *
 * 持久化模型：每個下載任務在其目標目錄下有一個以 info-hash 命名的
 * {@code <info-hash>.bt4j} 檔（引擎自管）。同一目錄可並存多個不同 torrent 的 .bt4j。
 * <ul>
 *   <li>{@link #createDownloadJob} 當場建立 {@code <info-hash>.bt4j}（同 torrent 的檔已存在則拋錯）。</li>
 *   <li>{@link #restoreDownloadJobs} 掃描目錄的所有 .bt4j，回傳 job 清單（沒有則回空清單）。</li>
 *   <li>下載完成且不做種 → 刪除 .bt4j；若做種 → 保留（做種中重啟仍可 restore）。</li>
 *   <li>{@link #stop} 硬停、保留檔案與 .bt4j（可再 restore）。</li>
 *   <li>{@link #deleteJob} 只刪除 .bt4j、保留已下載檔案。</li>
 * </ul>
 * {@link #download} 以 info-hash 去重：同一 torrent 重複 download 會回傳同一個既有 task。
 *
 * <pre>{@code
 * try (Bt bt = Bt.builder().listenPort(6881).downloadRateLimit(0).build()) {
 *     TorrentContent content = bt.fromMagnet("magnet:?xt=urn:btih:...", Duration.ofMinutes(3));
 *     List<TorrentContentFile> wanted = content.getFileList().stream()
 *             .filter(f -> f.path().endsWith(".mp4")).toList();
 *     TorrentDownloadJob job = bt.createDownloadJob(wanted, Path.of("/data/movie"), true);
 *     TorrentDownloadTask task = bt.download(job);
 *     // 輪詢 task.progress() / task.fileProgress() 更新 UI
 *
 *     // 重啟後恢復整個目錄的所有任務（download 冪等，重複自動略過）：
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

    // ---- 取得 torrent 內容 ----

    /** 解析 .torrent 檔，立即回傳內容。 */
    public TorrentContent fromTorrent(Path torrentFile) {
        return new TorrentContentImpl(Metainfo.parse(torrentFile));
    }

    public TorrentContent fromTorrent(File torrentFile) {
        return fromTorrent(torrentFile.toPath());
    }

    /**
     * 從磁力連結取得內容。**阻塞**直到 metadata 到齊或逾時
     * （magnet 只有 info-hash，檔案清單需向 swarm 索取）。取得後即丟棄暫時 session，不佔資源。
     *
     * @throws TimeoutException 逾時仍無法取得（例如死種）
     */
    public TorrentContent fromMagnet(String magnetUri, Duration timeout)
            throws TimeoutException, InterruptedException {
        TorrentSession session = client.addMagnet(magnetUri);
        try {
            Metainfo metainfo = session.awaitMetadata(timeout);
            return new TorrentContentImpl(metainfo);
        } finally {
            client.remove(session.infoHash()); // 丟棄取 metadata 的暫時 session
        }
    }

    // ---- 建立/還原任務 ----

    /**
     * 建立新的下載任務並在 targetDir 當場寫入 {@code <info-hash>.bt4j}。
     *
     * @param filesToDownload    要下載的檔案；**必須全部來自同一個 TorrentContent**，否則拋錯。空清單＝全部檔案
     * @param targetDir          目標目錄（同目錄可並存其他 torrent 的 .bt4j）
     * @param seedAfterComplete  完成後是否移入做種列表
     * @throws IllegalArgumentException filesToDownload 混入不同種子的檔案
     * @throws IllegalStateException    此 torrent 的 {@code <info-hash>.bt4j} 已存在於 targetDir
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
            throw new IllegalStateException("此 torrent 的 .bt4j 已存在: " + bt4j);
        }
        TorrentDownloadJobImpl job = new TorrentDownloadJobImpl(
                content, indices, targetDir, seedAfterComplete, false, null);
        // 當場寫入初始 .bt4j（完成數 0）
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
     * 掃描 targetDir 內所有 {@code <info-hash>.bt4j}，各還原成一個 job（重啟續傳）。
     * 目錄沒有任何 .bt4j 時回傳空清單（不拋錯）。壞掉/無法解析的個別 .bt4j 會略過。
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
            throw new UncheckedIOException("掃描目錄失敗: " + targetDir, e);
        }
        return jobs;
    }

    public List<TorrentDownloadJob> restoreDownloadJobs(File targetDir) {
        return restoreDownloadJobs(targetDir.toPath());
    }

    // ---- 執行與查詢 ----

    /** 開始執行任務，回傳可輪詢進度的把手。同一 torrent 重複呼叫回傳既有 task。 */
    public TorrentDownloadTask download(TorrentDownloadJob job) {
        TorrentDownloadJobImpl impl = (TorrentDownloadJobImpl) job;
        InfoHash infoHash = impl.content.metainfo.infoHash();
        TorrentDownloadTaskImpl existing = tasks.get(infoHash);
        if (existing != null) {
            return existing; // 冪等：已在執行
        }
        Path bt4j = bt4jPath(impl.targetDir, impl.content.infoHashHex());
        TorrentSession session;
        if (impl.fromRestore) {
            session = client.restore(impl.resumeData); // 信任 .bt4j 的 bitfield，快速續傳
        } else {
            session = client.addTorrent(impl.content.metainfo);
            session.start(new DownloadPlan(impl.targetDir, impl.selectedIndices, impl.seedAfter)); // 內含 recheck
        }
        TorrentDownloadTaskImpl task = new TorrentDownloadTaskImpl(session, impl.content, bt4j, impl.seedAfter);
        tasks.put(infoHash, task);
        session.addListener(new CompletionListener(task));
        LOG.log(Level.DEBUG, () -> "download started " + impl.content.infoHashHex());
        return task;
    }

    /** 目前下載中的任務。 */
    public List<TorrentDownloadTask> getDownloadTaskList() {
        return tasks.values().stream()
                .filter(t -> t.sessionState() == SessionState.DOWNLOADING)
                .map(t -> (TorrentDownloadTask) t)
                .toList();
    }

    /** 目前做種中的任務。 */
    public List<TorrentDownloadTask> getSeedingTaskList() {
        return tasks.values().stream()
                .filter(t -> t.sessionState() == SessionState.SEEDING)
                .map(t -> (TorrentDownloadTask) t)
                .toList();
    }

    // ---- 生命週期控制 ----

    /** 硬停任務（不暫停）。保留已下載檔案與 .bt4j，之後可 {@link #restoreDownloadJobs} 續傳。 */
    public void stop(TorrentDownloadTask task) {
        TorrentDownloadTaskImpl impl = (TorrentDownloadTaskImpl) task;
        InfoHash infoHash = impl.content.metainfo.infoHash();
        persist(impl); // 停止前寫入最新進度到 .bt4j
        tasks.remove(infoHash);
        lastPersistedBytes.remove(infoHash);
        client.remove(infoHash);
        LOG.log(Level.DEBUG, () -> "stopped " + impl.content.infoHashHex());
    }

    /** 停止並刪除該任務的 .bt4j 檔（保留已下載的資料檔案）。 */
    public void deleteJob(TorrentDownloadTask task) {
        TorrentDownloadTaskImpl impl = (TorrentDownloadTaskImpl) task;
        InfoHash infoHash = impl.content.metainfo.infoHash();
        tasks.remove(infoHash);
        lastPersistedBytes.remove(infoHash);
        client.remove(infoHash);
        deleteQuietly(impl.bt4jPath);
        LOG.log(Level.DEBUG, () -> "deleted job " + impl.content.infoHashHex());
    }

    /** 關閉引擎：停止所有任務、DHT 與 listen socket。已下載檔案與 .bt4j 保留。 */
    @Override
    public void close() {
        closed = true;
        for (TorrentDownloadTaskImpl task : tasks.values()) {
            persist(task); // 關閉前保存進度
        }
        tasks.clear();
        client.close();
    }

    // ---- 完成事件：依 seedAfter 保留或刪除 .bt4j ----

    private final class CompletionListener implements SessionListener {
        private final TorrentDownloadTaskImpl task;

        CompletionListener(TorrentDownloadTaskImpl task) {
            this.task = task;
        }

        @Override
        public void onDownloadCompleted(TorrentSession session) {
            if (task.seedAfter) {
                persist(task); // 做種：更新 .bt4j 為完成狀態，繼續保留
            } else {
                InfoHash infoHash = session.infoHash();
                tasks.remove(infoHash);
                lastPersistedBytes.remove(infoHash);
                deleteQuietly(task.bt4jPath); // 完成且不做種：刪除 .bt4j
                client.remove(infoHash);
                LOG.log(Level.DEBUG, () -> "completed (no seed), removed .bt4j: " + infoHash.hex());
            }
        }
    }

    // ---- 週期持久化 ----

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

    /** 若進度有變化，原子寫入該任務的 .bt4j。 */
    private void persist(TorrentDownloadTaskImpl task) {
        if (!tasks.containsKey(task.content.metainfo.infoHash())) {
            return; // 已被移除（例如剛完成刪檔），避免重建
        }
        ResumeData resume;
        try {
            resume = task.session.resumeData();
        } catch (RuntimeException e) {
            return; // metadata 未就緒等
        }
        InfoHash infoHash = task.content.metainfo.infoHash();
        long bytes = resume.completedPieces().cardinality();
        Long last = lastPersistedBytes.get(infoHash);
        if (last != null && last == bytes) {
            return; // 沒變化
        }
        writeResume(task.bt4jPath, resume);
        lastPersistedBytes.put(infoHash, bytes);
    }

    // ---- 工具 ----

    private static Path bt4jPath(Path targetDir, String infoHashHex) {
        return targetDir.resolve(infoHashHex + BT4J_SUFFIX);
    }

    /** 原子寫入：先寫 .tmp 再 rename，避免寫到一半 crash 造成壞檔。 */
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
            throw new IllegalArgumentException("filesToDownload 不可為空（全選請傳整個 getFileList()）");
        }
        TorrentContent content = files.getFirst().content();
        for (TorrentContentFile file : files) {
            if (file.content() != content) {
                throw new IllegalArgumentException("filesToDownload 含不同 torrent 的檔案，不允許混合");
            }
        }
        return (TorrentContentImpl) content;
    }

    /** 引擎設定。 */
    public static final class Builder {

        private final BtClient.Builder clientBuilder = BtClient.builder();

        private Builder() {
        }

        /** TCP listen port（同時作為 DHT UDP port）。0＝系統指派。預設 6881。 */
        public Builder listenPort(int port) {
            clientBuilder.listenPort(port);
            return this;
        }

        /** 停用 DHT（預設啟用）。 */
        public Builder dhtEnabled(boolean enabled) {
            clientBuilder.dhtEnabled(enabled);
            return this;
        }

        /** 全域下載速率上限（bytes/s，0＝不限）。 */
        public Builder downloadRateLimit(long bytesPerSec) {
            clientBuilder.downloadRateLimit(bytesPerSec);
            return this;
        }

        /** 全域上傳速率上限（bytes/s，0＝不限）。 */
        public Builder uploadRateLimit(long bytesPerSec) {
            clientBuilder.uploadRateLimit(bytesPerSec);
            return this;
        }

        /** 每個 torrent 的最大 peer 連線數，預設 30。 */
        public Builder maxPeersPerTorrent(int max) {
            clientBuilder.maxPeersPerTorrent(max);
            return this;
        }

        public Bt build() {
            return new Bt(this);
        }
    }
}
