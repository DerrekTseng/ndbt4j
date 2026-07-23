package net.derrek.bt4j;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import net.derrek.bt4j.metainfo.Metainfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** facade（Bt）端到端：fromTorrent → createDownloadJob → download → 完成 → .bt4j 生命週期 → restore。 */
class FacadeTest {

    private static final int PIECE_LENGTH = 16384;

    private static Bt engine() {
        return Bt.builder().listenPort(0).dhtEnabled(false).maxPeersPerTorrent(5).build();
    }

    private static void awaitUntil(java.util.function.BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("等候條件逾時");
            }
            Thread.sleep(30);
        }
    }

    /** 建立 seeder + 假 tracker，回傳指向 tracker 的 metainfo。 */
    private record Swarm(TestSeeder seeder, FakeHttpTracker tracker, Metainfo metainfo) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            tracker.close();
            seeder.close();
        }
    }

    private static Swarm swarm(byte[] content, String name) throws Exception {
        Metainfo seederMeta = TorrentFixtures.singleFile(name, content, PIECE_LENGTH, "http://placeholder/");
        TestSeeder seeder = new TestSeeder(seederMeta, content);
        FakeHttpTracker tracker = new FakeHttpTracker(seeder.port());
        Metainfo meta = TorrentFixtures.singleFile(name, content, PIECE_LENGTH, tracker.announceUrl());
        return new Swarm(seeder, tracker, meta);
    }

    @Test
    void fullDownloadNoSeedDeletesBt4jWhenDone(@TempDir Path tmp) throws Exception {
        byte[] content = TorrentFixtures.randomBytes(80_000, 1);
        try (Swarm sw = swarm(content, "movie.bin")) {
            Path torrentFile = tmp.resolve("movie.torrent");
            Files.write(torrentFile, sw.metainfo().toTorrentBytes());

            try (Bt bt = engine()) {
                TorrentContent tc = bt.fromTorrent(torrentFile);
                assertEquals("movie.bin", tc.name());
                assertEquals(80_000, tc.totalSize());
                assertEquals(1, tc.getFileList().size());

                Path targetDir = tmp.resolve("dl");
                TorrentDownloadJob job = bt.createDownloadJob(tc.getFileList(), targetDir, false);
                Path bt4j = targetDir.resolve(tc.infoHashHex() + ".bt4j");
                assertTrue(Files.exists(bt4j), "createDownloadJob 應當場建立 .bt4j");

                TorrentDownloadTask task = bt.download(job);
                awaitUntil(() -> task.state() != TaskState.DOWNLOADING, 30_000);

                assertEquals(TaskState.STOPPED, task.state()); // 無做種 → 完成即停止
                assertEquals(1.0, task.progress(), 1e-9);
                assertArrayEquals(content, Files.readAllBytes(targetDir.resolve("movie.bin")));

                awaitUntil(() -> !Files.exists(bt4j), 5_000); // 完成且不做種 → .bt4j 刪除
                assertTrue(bt.getDownloadTaskList().isEmpty());
                assertTrue(bt.getSeedingTaskList().isEmpty());
            }
        }
    }

    @Test
    void seedAfterKeepsBt4jAndListsInSeeding(@TempDir Path tmp) throws Exception {
        byte[] content = TorrentFixtures.randomBytes(60_000, 2);
        try (Swarm sw = swarm(content, "keep.bin")) {
            Path torrentFile = tmp.resolve("keep.torrent");
            Files.write(torrentFile, sw.metainfo().toTorrentBytes());

            try (Bt bt = engine()) {
                TorrentContent tc = bt.fromTorrent(torrentFile);
                Path targetDir = tmp.resolve("dl");
                TorrentDownloadJob job = bt.createDownloadJob(tc.getFileList(), targetDir, true);
                TorrentDownloadTask task = bt.download(job);

                awaitUntil(() -> task.state() == TaskState.SEEDING, 30_000);
                Path bt4j = targetDir.resolve(tc.infoHashHex() + ".bt4j");
                assertTrue(Files.exists(bt4j), "做種中應保留 .bt4j");
                assertEquals(1, bt.getSeedingTaskList().size());
                assertTrue(bt.getDownloadTaskList().isEmpty());

                bt.stop(task); // 硬停：保留 .bt4j
                assertTrue(Files.exists(bt4j));
                assertTrue(bt.getSeedingTaskList().isEmpty());
            }
        }
    }

    @Test
    void createDownloadJobRejectsDuplicateAndMixedTorrents(@TempDir Path tmp) throws Exception {
        byte[] c1 = TorrentFixtures.randomBytes(20_000, 3);
        byte[] c2 = TorrentFixtures.randomBytes(20_000, 4);
        try (Swarm sw1 = swarm(c1, "a.bin"); Swarm sw2 = swarm(c2, "b.bin")) {
            Path t1 = tmp.resolve("a.torrent");
            Path t2 = tmp.resolve("b.torrent");
            Files.write(t1, sw1.metainfo().toTorrentBytes());
            Files.write(t2, sw2.metainfo().toTorrentBytes());

            try (Bt bt = engine()) {
                TorrentContent tc1 = bt.fromTorrent(t1);
                TorrentContent tc2 = bt.fromTorrent(t2);
                Path dir = tmp.resolve("dl");

                // 混入不同 torrent 的檔案 → 拋錯
                List<TorrentContentFile> mixed = List.of(tc1.getFileList().get(0), tc2.getFileList().get(0));
                assertThrows(IllegalArgumentException.class, () -> bt.createDownloadJob(mixed, dir, false));

                bt.createDownloadJob(tc1.getFileList(), dir, false);
                // 同 torrent 的 .bt4j 已存在 → 拋錯
                assertThrows(IllegalStateException.class, () -> bt.createDownloadJob(tc1.getFileList(), dir, false));
                // 但不同 torrent 可共存於同目錄
                bt.createDownloadJob(tc2.getFileList(), dir, false);
                assertTrue(Files.exists(dir.resolve(tc1.infoHashHex() + ".bt4j")));
                assertTrue(Files.exists(dir.resolve(tc2.infoHashHex() + ".bt4j")));
            }
        }
    }

    @Test
    void freshDownloadSalvagesExistingPartialFilesViaRecheck(@TempDir Path tmp) throws Exception {
        byte[] content = TorrentFixtures.randomBytes(100_000, 7); // 7 pieces
        try (Swarm sw = swarm(content, "partial.bin")) {
            Path torrentFile = tmp.resolve("partial.torrent");
            Files.write(torrentFile, sw.metainfo().toTorrentBytes());

            try (Bt bt = engine()) {
                TorrentContent tc = bt.fromTorrent(torrentFile);
                Path targetDir = tmp.resolve("dl");
                Files.createDirectories(targetDir);
                // 模擬「上次下載了前 3 個 piece」：預先在目標目錄放半成品檔
                int partialBytes = 3 * PIECE_LENGTH;
                Files.write(targetDir.resolve("partial.bin"), java.util.Arrays.copyOf(content, partialBytes));

                TorrentDownloadJob job = bt.createDownloadJob(tc.getFileList(), targetDir, false);
                TorrentDownloadTask task = bt.download(job); // start() 會 recheck 救回既有 3 個 piece
                awaitUntil(() -> task.state() != TaskState.DOWNLOADING, 30_000);
                assertArrayEquals(content, Files.readAllBytes(targetDir.resolve("partial.bin")));

                // seeder 只需上傳缺少的部分（recheck 救回的 3 個 piece 不重下）
                long seederUploaded = sw.seeder().uploadedBytesForTest();
                assertTrue(seederUploaded < content.length - PIECE_LENGTH,
                        "recheck 應救回既有半成品，做種端上傳 " + seederUploaded + " 應遠少於整檔");
            }
        }
    }

    @Test
    void restoreDownloadJobsScansDirectory(@TempDir Path tmp) throws Exception {
        byte[] c1 = TorrentFixtures.randomBytes(20_000, 5);
        byte[] c2 = TorrentFixtures.randomBytes(20_000, 6);
        try (Swarm sw1 = swarm(c1, "x.bin"); Swarm sw2 = swarm(c2, "y.bin")) {
            Path dir = tmp.resolve("dl");
            String hash1;
            String hash2;
            try (Bt bt = engine()) {
                TorrentContent tc1 = bt.fromTorrent(writeTorrent(tmp, "x", sw1.metainfo()));
                TorrentContent tc2 = bt.fromTorrent(writeTorrent(tmp, "y", sw2.metainfo()));
                hash1 = tc1.infoHashHex();
                hash2 = tc2.infoHashHex();
                bt.createDownloadJob(tc1.getFileList(), dir, true);
                bt.createDownloadJob(tc2.getFileList(), dir, false);
            }

            // 新引擎重啟後掃描目錄
            try (Bt bt = engine()) {
                List<TorrentDownloadJob> jobs = bt.restoreDownloadJobs(dir);
                assertEquals(2, jobs.size());
                assertTrue(jobs.stream().map(j -> j.content().infoHashHex()).toList().containsAll(List.of(hash1, hash2)));

                // 空目錄 → 空清單（不拋錯）
                assertTrue(bt.restoreDownloadJobs(tmp.resolve("empty")).isEmpty());
            }
        }
    }

    private static Path writeTorrent(Path tmp, String name, Metainfo meta) throws Exception {
        Path file = tmp.resolve(name + ".torrent");
        Files.write(file, meta.toTorrentBytes());
        return file;
    }
}
