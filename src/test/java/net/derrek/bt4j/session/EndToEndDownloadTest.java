package net.derrek.bt4j.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.derrek.bt4j.FakeHttpTracker;
import net.derrek.bt4j.TestSeeder;
import net.derrek.bt4j.TorrentFixtures;
import net.derrek.bt4j.metainfo.FileEntry;
import net.derrek.bt4j.metainfo.Metainfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 端到端驗收（M3）：本地假 tracker + 測試 seeder，
 * 完整走過 announce → compact peers → handshake → 下載 → SHA-1 驗證 → 檔案落地。
 */
class EndToEndDownloadTest {

    private static final int PIECE_LENGTH = 16384;

    @Test
    void downloadSingleFileTorrentEndToEnd(@TempDir Path tmp) throws Exception {
        byte[] content = TorrentFixtures.randomBytes(100_000, 42); // 7 pieces（最後一個 1696 bytes）
        Metainfo bootstrap = TorrentFixtures.singleFile("e2e.bin", content, PIECE_LENGTH, "http://placeholder/");

        try (TestSeeder seeder = new TestSeeder(bootstrap, content);
             FakeHttpTracker tracker = new FakeHttpTracker(seeder.port())) {

            Metainfo metainfo = TorrentFixtures.singleFile("e2e.bin", content, PIECE_LENGTH, tracker.announceUrl());
            assertEquals(bootstrap.infoHash(), metainfo.infoHash()); // announce 不影響 info-hash

            try (BtClient client = BtClient.builder().listenPort(6899).maxPeersPerTorrent(5).build()) {
                TorrentSession session = client.addTorrent(metainfo);
                assertEquals(SessionState.METADATA_READY, session.state());

                CountDownLatch completed = new CountDownLatch(1);
                CountDownLatch fileDone = new CountDownLatch(1);
                session.addListener(new SessionListener() {
                    @Override
                    public void onDownloadCompleted(TorrentSession s) {
                        completed.countDown();
                    }

                    @Override
                    public void onFileCompleted(TorrentSession s, FileEntry file) {
                        fileDone.countDown();
                    }
                });

                session.start(DownloadPlan.allFiles(tmp));
                assertTrue(completed.await(30, TimeUnit.SECONDS), "30 秒內未完成下載");
                assertTrue(fileDone.await(5, TimeUnit.SECONDS), "onFileCompleted 未觸發");
                assertEquals(SessionState.SEEDING, session.state());

                TorrentStats stats = session.stats();
                assertEquals(1.0, stats.progress(), 1e-9);
                assertEquals(100_000, stats.downloadedBytes());
            }
        }
        assertArrayEquals(content, Files.readAllBytes(tmp.resolve("e2e.bin")));
    }
}
