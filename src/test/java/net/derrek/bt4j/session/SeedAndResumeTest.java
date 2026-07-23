package net.derrek.bt4j.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * M8 驗收：真正的做種（連入 listener + 上傳路徑）與重啟續傳。
 * 一個 bt4j client 做種、另一個 bt4j client 下載，全程走真實 wire protocol。
 */
class SeedAndResumeTest {

    private static final int PIECE_LENGTH = 16384;

    private static BtClient client(int port) {
        return BtClient.builder().listenPort(port).dhtEnabled(false).maxPeersPerTorrent(5).build();
    }

    /** 建立做種 client：完整檔案已在磁碟，以 resume（全完成）還原為 SEEDING。 */
    private static BtClient startSeeder(Metainfo meta, byte[] content, Path seederDir) throws Exception {
        Files.createDirectories(seederDir);
        Files.write(seederDir.resolve(meta.name()), content);
        Bitfield allComplete = new Bitfield(meta.pieceCount());
        allComplete.setAll();
        ResumeData resume = new ResumeData(meta.toTorrentBytes(), allComplete, Set.of(), seederDir, 0, false);

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
                assertTrue(done.await(30, TimeUnit.SECONDS), "30 秒內未從 bt4j seeder 下載完成");
                assertArrayEquals(content, Files.readAllBytes(leecherDir.resolve("share.bin")));

                // 做種端有實際上傳量
                assertTrue(seeder.sessions().getFirst().stats().uploadedBytes() >= 100_000,
                        "做種端應累計上傳 ≥ 檔案大小");
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

            // 模擬「上次下載到一半」：前 3 個 piece 已完成並落地
            Path leecherDir = tmp.resolve("leecher");
            Files.createDirectories(leecherDir);
            int completedPieces = 3;
            int completedBytes = completedPieces * PIECE_LENGTH;
            Files.write(leecherDir.resolve("resume.bin"), Arrays.copyOf(content, completedBytes));
            Bitfield partial = new Bitfield(meta.pieceCount());
            for (int p = 0; p < completedPieces; p++) {
                partial.set(p);
            }
            ResumeData resume = new ResumeData(meta.toTorrentBytes(), partial, Set.of(), leecherDir, 0, false);

            try (BtClient leecher = client(0)) {
                TorrentSession session = leecher.restore(resume);
                assertEquals(SessionState.DOWNLOADING, session.state()); // 尚未完成 → 續傳

                CountDownLatch done = new CountDownLatch(1);
                session.addListener(new SessionListener() {
                    @Override
                    public void onDownloadCompleted(TorrentSession s) {
                        done.countDown();
                    }
                });
                assertTrue(done.await(30, TimeUnit.SECONDS), "30 秒內未完成續傳");
                assertArrayEquals(content, Files.readAllBytes(leecherDir.resolve("resume.bin")));

                // 續傳只向 seeder 索取缺少的 4 個 piece（50848 bytes），不重下已完成的 3 個；
                // 故做種端上傳量遠少於整個檔案（若 resume 失效會上傳滿 100000）。
                long seederUploaded = seeder.sessions().getFirst().stats().uploadedBytes();
                long missingBytes = content.length - completedBytes;
                assertTrue(seederUploaded < content.length - PIECE_LENGTH,
                        "做種端上傳 " + seederUploaded + " 應遠少於整檔（證明已完成 piece 未重下）");
                assertTrue(seederUploaded >= missingBytes,
                        "做種端至少要上傳缺少的 " + missingBytes + " bytes，實際 " + seederUploaded);
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
        ResumeData resume = new ResumeData(meta.toTorrentBytes(), full, Set.of(), dir, 999, true);

        try (BtClient client = client(0)) {
            TorrentSession session = client.restore(resume);
            assertEquals(SessionState.STOPPED, session.state());
            assertEquals(999, session.stats().uploadedBytes());
        }
    }
}
