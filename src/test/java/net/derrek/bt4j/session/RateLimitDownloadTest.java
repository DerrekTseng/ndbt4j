package net.derrek.bt4j.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.derrek.bt4j.FakeHttpTracker;
import net.derrek.bt4j.TestSeeder;
import net.derrek.bt4j.TorrentFixtures;
import net.derrek.bt4j.metainfo.Metainfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 下載限速：設定較低的下載上限後，完成時間應明顯拉長且內容仍正確。 */
class RateLimitDownloadTest {

    private static final int PIECE_LENGTH = 16384;

    @Test
    void downloadRespectsRateLimit(@TempDir Path tmp) throws Exception {
        byte[] content = TorrentFixtures.randomBytes(600_000, 9); // ~586 KiB
        Metainfo seederMeta = TorrentFixtures.singleFile("rl.bin", content, PIECE_LENGTH, "http://unused/");

        try (TestSeeder seeder = new TestSeeder(seederMeta, content);
             FakeHttpTracker tracker = new FakeHttpTracker(seeder.port())) {

            Metainfo meta = TorrentFixtures.singleFile("rl.bin", content, PIECE_LENGTH, tracker.announceUrl());
            // 限速 200 KiB/s：600 KB 內容扣掉 256 KiB 突發量後，穩態約需 ~1.7s
            try (BtClient client = BtClient.builder()
                    .listenPort(0).dhtEnabled(false).maxPeersPerTorrent(3)
                    .downloadRateLimit(200 * 1024)
                    .build()) {

                TorrentSession session = client.addTorrent(meta);
                CountDownLatch done = new CountDownLatch(1);
                session.addListener(new SessionListener() {
                    @Override
                    public void onDownloadCompleted(TorrentSession s) {
                        done.countDown();
                    }
                });

                long start = System.nanoTime();
                session.start(DownloadPlan.allFiles(tmp));
                assertTrue(done.await(30, TimeUnit.SECONDS), "限速下仍應在 30 秒內完成");
                double seconds = (System.nanoTime() - start) / 1e9;

                assertArrayEquals(content, Files.readAllBytes(tmp.resolve("rl.bin")));
                // 若完全不限速，本地 loopback 幾乎瞬間完成（<0.3s）；限速後應明顯拉長
                assertTrue(seconds > 1.0, "限速應讓完成時間明顯拉長，實際 " + seconds + "s");
            }
        }
    }
}
