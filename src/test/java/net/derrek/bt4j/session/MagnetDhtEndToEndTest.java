package net.derrek.bt4j.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.derrek.bt4j.TestSeeder;
import net.derrek.bt4j.TorrentFixtures;
import net.derrek.bt4j.dht.DhtClient;
import net.derrek.bt4j.metainfo.Metainfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M7 驗收：無 tracker、無 x.pe 的磁力連結，
 * 純靠 DHT（本地小型網路）找到 seeder → BEP 9 取 metadata → 下載完成。
 */
class MagnetDhtEndToEndTest {

    private static final int PIECE_LENGTH = 16384;

    @Test
    void trackerlessMagnetViaDht(@TempDir Path tmp) throws Exception {
        byte[] content = TorrentFixtures.randomBytes(60_000, 99); // 4 pieces
        Metainfo source = TorrentFixtures.singleFile("dht.bin", content, PIECE_LENGTH, "http://unused/");

        try (TestSeeder seeder = new TestSeeder(source, content);
             DhtClient hub = new DhtClient(0, List.of())) {
            hub.start();
            InetSocketAddress hubAddress = new InetSocketAddress("127.0.0.1", hub.port());

            // seeder 側的 DHT 節點：向 hub 宣告「此 info-hash 的 peer 在 seeder 的 TCP port」
            try (DhtClient seederDht = new DhtClient(0, List.of(hubAddress))) {
                seederDht.start();
                assertTrue(seederDht.awaitBootstrap(Duration.ofSeconds(10)));
                seederDht.announce(source.infoHash(), seeder.port()).get(15, TimeUnit.SECONDS);

                // 客戶端：磁力連結只有 info-hash，唯一的 peer 來源是 DHT
                String magnet = "magnet:?xt=urn:btih:" + source.infoHash().hex();
                try (BtClient client = BtClient.builder()
                        .listenPort(6896)
                        .dhtBootstrapNodes(List.of(hubAddress))
                        .maxPeersPerTorrent(5)
                        .build()) {

                    TorrentSession session = client.addMagnet(magnet);
                    Metainfo fetched = session.awaitMetadata(Duration.ofSeconds(30));
                    assertEquals("dht.bin", fetched.name());
                    assertEquals(source.infoHash(), fetched.infoHash());

                    CountDownLatch completed = new CountDownLatch(1);
                    session.addListener(new SessionListener() {
                        @Override
                        public void onDownloadCompleted(TorrentSession s) {
                            completed.countDown();
                        }
                    });
                    session.start(DownloadPlan.allFiles(tmp));
                    assertTrue(completed.await(60, TimeUnit.SECONDS), "60 秒內未完成下載");
                    assertEquals(SessionState.SEEDING, session.state());
                }
            }
        }
        assertArrayEquals(content, Files.readAllBytes(tmp.resolve("dht.bin")));
    }
}
