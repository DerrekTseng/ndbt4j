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

/** Download rate limiting: after setting a low download cap, completion time should visibly lengthen while content stays correct. */
class RateLimitDownloadTest {

    private static final int PIECE_LENGTH = 16384;

    @Test
    void downloadRespectsRateLimit(@TempDir Path tmp) throws Exception {
        byte[] content = TorrentFixtures.randomBytes(600_000, 9); // ~586 KiB
        Metainfo seederMeta = TorrentFixtures.singleFile("rl.bin", content, PIECE_LENGTH, "http://unused/");

        try (TestSeeder seeder = new TestSeeder(seederMeta, content);
             FakeHttpTracker tracker = new FakeHttpTracker(seeder.port())) {

            Metainfo meta = TorrentFixtures.singleFile("rl.bin", content, PIECE_LENGTH, tracker.announceUrl());
            // Rate limit 200 KiB/s: after subtracting the 256 KiB burst from the 600 KB content, steady state takes ~1.7s
            try (BtClient client = BtClient.builder().lsdEnabled(false)
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
                assertTrue(done.await(30, TimeUnit.SECONDS), "should still complete within 30 seconds under rate limiting");
                double seconds = (System.nanoTime() - start) / 1e9;

                assertArrayEquals(content, Files.readAllBytes(tmp.resolve("rl.bin")));
                // With no rate limiting, local loopback finishes almost instantly (<0.3s); with rate limiting it should visibly lengthen
                assertTrue(seconds > 1.0, "rate limiting should visibly lengthen completion time, actual " + seconds + "s");
            }
        }
    }
}
