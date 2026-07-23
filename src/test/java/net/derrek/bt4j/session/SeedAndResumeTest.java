package net.derrek.bt4j.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.derrek.bt4j.FakeHttpTracker;
import net.derrek.bt4j.TorrentFixtures;
import net.derrek.bt4j.metainfo.Metainfo;
import net.derrek.bt4j.piece.Bitfield;
import net.derrek.bt4j.storage.ResumeData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M8 acceptance: real seeding (inbound listener + upload path) and resume after restart.
 * One bt4j client seeds, another bt4j client downloads, using the real wire protocol throughout.
 */
class SeedAndResumeTest {

    private static final int PIECE_LENGTH = 16384;

    private static BtClient client(int port) {
        return BtClient.builder().listenPort(port).dhtEnabled(false).maxPeersPerTorrent(5).build();
    }

    /** Create a seeding client: the complete file is already on disk, restored to SEEDING via resume (fully complete). */
    private static BtClient startSeeder(Metainfo meta, byte[] content, Path seederDir) throws Exception {
        Files.createDirectories(seederDir);
        Files.write(seederDir.resolve(meta.name()), content);
        Bitfield allComplete = new Bitfield(meta.pieceCount());
        allComplete.setAll();
        ResumeData resume = new ResumeData(meta.toTorrentBytes(), allComplete, Set.of(), seederDir, 0, false, true);

        BtClient seeder = client(0);
        TorrentSession session = seeder.restore(resume);
        assertEquals(SessionState.SEEDING, session.state());
        return seeder;
    }

    @Test
    void oneClientSeedsAnotherDownloads(@TempDir Path tmp) throws Exception {
        byte[] content = TorrentFixtures.randomBytes(100_000, 55); // 7 pieces
        Path seederDir = tmp.resolve("seeder");
        Metainfo seederMeta = TorrentFixtures.singleFile("share.bin", content, PIECE_LENGTH, "http://unused/");

        try (BtClient seeder = startSeeder(seederMeta, content, seederDir);
             FakeHttpTracker tracker = new FakeHttpTracker(seeder.listenPort())) {

            Metainfo leecherMeta = TorrentFixtures.singleFile("share.bin", content, PIECE_LENGTH, tracker.announceUrl());
            assertEquals(seederMeta.infoHash(), leecherMeta.infoHash());

            Path leecherDir = tmp.resolve("leecher");
            try (BtClient leecher = client(0)) {
                TorrentSession session = leecher.addTorrent(leecherMeta);
                CountDownLatch done = new CountDownLatch(1);
                session.addListener(new SessionListener() {
                    @Override
                    public void onDownloadCompleted(TorrentSession s) {
                        done.countDown();
                    }
                });
                session.start(DownloadPlan.allFiles(leecherDir));
                assertTrue(done.await(30, TimeUnit.SECONDS), "did not finish downloading from the bt4j seeder within 30 seconds");
                assertArrayEquals(content, Files.readAllBytes(leecherDir.resolve("share.bin")));

                // The seeder has actual upload volume
                assertTrue(seeder.sessions().getFirst().stats().uploadedBytes() >= 100_000,
                        "seeder should accumulate uploads >= file size");
            }
        }
    }

    @Test
    void resumeSkipsCompletedPiecesAndDownloadsRest(@TempDir Path tmp) throws Exception {
        byte[] content = TorrentFixtures.randomBytes(100_000, 66); // 7 pieces
        Path seederDir = tmp.resolve("seeder");
        Metainfo seederMeta = TorrentFixtures.singleFile("resume.bin", content, PIECE_LENGTH, "http://unused/");

        try (BtClient seeder = startSeeder(seederMeta, content, seederDir);
             FakeHttpTracker tracker = new FakeHttpTracker(seeder.listenPort())) {

            Metainfo meta = TorrentFixtures.singleFile("resume.bin", content, PIECE_LENGTH, tracker.announceUrl());

            // Simulate "last download stopped halfway": the first 3 pieces are complete and written to disk
            Path leecherDir = tmp.resolve("leecher");
            Files.createDirectories(leecherDir);
            int completedPieces = 3;
            int completedBytes = completedPieces * PIECE_LENGTH;
            Files.write(leecherDir.resolve("resume.bin"), Arrays.copyOf(content, completedBytes));
            Bitfield partial = new Bitfield(meta.pieceCount());
            for (int p = 0; p < completedPieces; p++) {
                partial.set(p);
            }
            ResumeData resume = new ResumeData(meta.toTorrentBytes(), partial, Set.of(), leecherDir, 0, false, true);

            try (BtClient leecher = client(0)) {
                TorrentSession session = leecher.restore(resume);
                assertEquals(SessionState.DOWNLOADING, session.state()); // not yet complete -> resume

                CountDownLatch done = new CountDownLatch(1);
                session.addListener(new SessionListener() {
                    @Override
                    public void onDownloadCompleted(TorrentSession s) {
                        done.countDown();
                    }
                });
                assertTrue(done.await(30, TimeUnit.SECONDS), "resume did not complete within 30 seconds");
                assertArrayEquals(content, Files.readAllBytes(leecherDir.resolve("resume.bin")));

                // Resume only requests the 4 missing pieces (50848 bytes) from the seeder, not re-downloading the 3 already done;
                // so the seeder's upload volume is far less than the whole file (if resume failed it would upload the full 100000).
                long seederUploaded = seeder.sessions().getFirst().stats().uploadedBytes();
                long missingBytes = content.length - completedBytes;
                assertTrue(seederUploaded < content.length - PIECE_LENGTH,
                        "seeder uploaded " + seederUploaded + " should be far less than the whole file (proving completed pieces were not re-downloaded)");
                assertTrue(seederUploaded >= missingBytes,
                        "seeder must upload at least the missing " + missingBytes + " bytes, actual " + seederUploaded);
            }
        }
    }

    @Test
    void stoppedResumeStaysStoppedWithoutNetworking(@TempDir Path tmp) throws Exception {
        byte[] content = TorrentFixtures.randomBytes(40_000, 77);
        Metainfo meta = TorrentFixtures.singleFile("stopped.bin", content, PIECE_LENGTH, "http://unused/");
        Path dir = tmp.resolve("d");
        Files.createDirectories(dir);
        Files.write(dir.resolve("stopped.bin"), content);
        Bitfield full = new Bitfield(meta.pieceCount());
        full.setAll();
        ResumeData resume = new ResumeData(meta.toTorrentBytes(), full, Set.of(), dir, 999, true, true);

        try (BtClient client = client(0)) {
            TorrentSession session = client.restore(resume);
            assertEquals(SessionState.STOPPED, session.state());
            assertEquals(999, session.stats().uploadedBytes());
        }
    }

    @Test
    void uploadRateLimitZeroSeederServesNothing(@TempDir Path tmp) throws Exception {
        byte[] content = TorrentFixtures.randomBytes(60_000, 200);
        Path seederDir = tmp.resolve("seeder");
        Metainfo seederMeta = TorrentFixtures.singleFile("block.bin", content, PIECE_LENGTH, "http://unused/");
        Files.createDirectories(seederDir);
        Files.write(seederDir.resolve("block.bin"), content);
        Bitfield allComplete = new Bitfield(seederMeta.pieceCount());
        allComplete.setAll();
        ResumeData resume = new ResumeData(seederMeta.toTorrentBytes(), allComplete, Set.of(), seederDir, 0, false, true);

        // Seeder uploadRateLimit(0) = no uploading at all
        try (BtClient seeder = BtClient.builder().listenPort(0).dhtEnabled(false)
                .maxPeersPerTorrent(5).uploadRateLimit(0).build()) {
            seeder.restore(resume);
            try (FakeHttpTracker tracker = new FakeHttpTracker(seeder.listenPort())) {
                Metainfo leecherMeta = TorrentFixtures.singleFile("block.bin", content, PIECE_LENGTH, tracker.announceUrl());
                Path leecherDir = tmp.resolve("leecher");
                try (BtClient leecher = client(0)) {
                    TorrentSession session = leecher.addTorrent(leecherMeta);
                    CountDownLatch done = new CountDownLatch(1);
                    session.addListener(new SessionListener() {
                        @Override
                        public void onDownloadCompleted(TorrentSession s) {
                            done.countDown();
                        }
                    });
                    session.start(DownloadPlan.allFiles(leecherDir));

                    // Seeder does not upload -> leecher gets no data and cannot complete
                    assertFalse(done.await(6, TimeUnit.SECONDS), "leecher should not complete the download when uploading is blocked");
                    assertEquals(0, session.stats().downloadedBytes(), "seeder should upload nothing at all");
                }
            }
        }
    }
}
