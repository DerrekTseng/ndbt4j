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

/** facade (Bt) end-to-end: fromTorrent -> createDownloadJob -> download -> complete -> .bt4j lifecycle -> restore. */
class FacadeTest {

    private static final int PIECE_LENGTH = 16384;

    private static Bt engine() {
        return Bt.builder().listenPort(0).dhtEnabled(false).maxPeersPerTorrent(5).build();
    }

    private static void awaitUntil(java.util.function.BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for condition");
            }
            Thread.sleep(30);
        }
    }

    /** Create a seeder + fake tracker, returning metainfo that points at the tracker. */
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
                assertTrue(Files.exists(bt4j), "createDownloadJob should create the .bt4j on the spot");

                TorrentDownloadTask task = bt.download(job);
                awaitUntil(() -> task.state() != TaskState.DOWNLOADING, 30_000);

                assertEquals(TaskState.STOPPED, task.state()); // no seeding -> stop on completion
                assertEquals(1.0, task.progress(), 1e-9);
                assertArrayEquals(content, Files.readAllBytes(targetDir.resolve("movie.bin")));

                awaitUntil(() -> !Files.exists(bt4j), 5_000); // complete and not seeding -> .bt4j deleted
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
                assertTrue(Files.exists(bt4j), "should keep the .bt4j while seeding");
                assertEquals(1, bt.getSeedingTaskList().size());
                assertTrue(bt.getDownloadTaskList().isEmpty());

                bt.stop(task); // hard stop: keep the .bt4j
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

                // Mixing files from different torrents -> throws
                List<TorrentContentFile> mixed = List.of(tc1.getFileList().get(0), tc2.getFileList().get(0));
                assertThrows(IllegalArgumentException.class, () -> bt.createDownloadJob(mixed, dir, false));

                bt.createDownloadJob(tc1.getFileList(), dir, false);
                // The .bt4j for the same torrent already exists -> throws
                assertThrows(IllegalStateException.class, () -> bt.createDownloadJob(tc1.getFileList(), dir, false));
                // But different torrents can coexist in the same directory
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
                // Simulate "last download got the first 3 pieces": pre-place a partial file in the target directory
                int partialBytes = 3 * PIECE_LENGTH;
                Files.write(targetDir.resolve("partial.bin"), java.util.Arrays.copyOf(content, partialBytes));

                TorrentDownloadJob job = bt.createDownloadJob(tc.getFileList(), targetDir, false);
                TorrentDownloadTask task = bt.download(job); // start() rechecks and salvages the existing 3 pieces
                awaitUntil(() -> task.state() != TaskState.DOWNLOADING, 30_000);
                assertArrayEquals(content, Files.readAllBytes(targetDir.resolve("partial.bin")));

                // The seeder only needs to upload the missing part (the 3 pieces salvaged by recheck are not re-downloaded)
                long seederUploaded = sw.seeder().uploadedBytesForTest();
                assertTrue(seederUploaded < content.length - PIECE_LENGTH,
                        "recheck should salvage the existing partial file; seeder uploaded " + seederUploaded + " should be far less than the whole file");
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

            // A new engine scans the directory after restart
            try (Bt bt = engine()) {
                List<TorrentDownloadJob> jobs = bt.restoreDownloadJobs(dir);
                assertEquals(2, jobs.size());
                assertTrue(jobs.stream().map(j -> j.content().infoHashHex()).toList().containsAll(List.of(hash1, hash2)));

                // Empty directory -> empty list (no throw)
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
