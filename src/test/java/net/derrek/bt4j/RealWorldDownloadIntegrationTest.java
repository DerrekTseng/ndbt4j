package net.derrek.bt4j;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real-world end-to-end (requires network, disabled by default):
 * A trackerless magnet link supplied by the user (doc/TEST-MAGNETS.md set 2),
 * relying purely on the public DHT to find peers -> fetch metadata -> actually download and verify pieces via SHA-1.
 *
 * To avoid pulling down the entire file, it stops as soon as "at least one piece passes verification (progress > 0)" --
 * this already proves the download loop of "connect to real peer -> request piece -> download -> verify" works on a real network.
 *
 * How to run: {@code mvn test -Dbt4j.integration=true -Dtest=RealWorldDownloadIntegrationTest}
 */
@EnabledIfSystemProperty(named = "bt4j.integration", matches = "true")
class RealWorldDownloadIntegrationTest {

    private static final String MAGNET = "magnet:?xt=urn:btih:617361908edde0130fb9e2ed9b854c1fcc467409";

    @Test
    void downloadsAndVerifiesRealPiecesFromPublicSwarm(@TempDir Path tmp) throws Exception {
        try (Bt bt = Bt.builder().listenPort(6889).build()) {
            TorrentContent content = bt.fromMagnet(MAGNET, Duration.ofMinutes(3));
            System.out.println("[real] metadata: " + content.name()
                    + ", files=" + content.getFileList().size() + ", total=" + content.totalSize());

            TorrentDownloadJob job = bt.createDownloadJob(content.getFileList(), tmp, false);
            TorrentDownloadTask task = bt.download(job);

            // Wait until at least one piece passes SHA-1 verification (progress > 0), or the whole thing completes
            long deadline = System.currentTimeMillis() + Duration.ofMinutes(4).toMillis();
            long lastLog = 0;
            while (task.progress() <= 0.0
                    && task.state() == TaskState.DOWNLOADING
                    && System.currentTimeMillis() < deadline) {
                if (System.currentTimeMillis() - lastLog > 5000) {
                    System.out.printf("[real] peers=%d  ↓%d KB/s  progress=%.3f%%%n",
                            task.connectedPeers(), task.downloadRate() / 1024, task.progress() * 100);
                    lastLog = System.currentTimeMillis();
                }
                Thread.sleep(500);
            }

            System.out.printf("[real] verified %d bytes (%.3f%%) from real peers%n",
                    task.downloadedBytes(), task.progress() * 100);
            assertFalse(task.state() == TaskState.ERROR, "session should not enter ERROR");
            assertTrue(task.progress() > 0.0,
                    "at least one piece should pass SHA-1 verification (the download loop works on a real swarm)");

            bt.stop(task); // proven, stop early (do not pull down the entire file)
        }
    }

    @Test
    void bothTestMagnetPairsShareInfoHash() {
        // Extra sanity check (no network needed): for each of the user's two link sets, the Base32 and hex forms are the same hash
        List<String[]> pairs = List.of(
                new String[] {"IF4ZTTPVIENGKIVL5M2MEBMUGSTJ2GCU", "417999cdf5411a6522abeb34c2059434a69d1854"},
                new String[] {"MFZWDEEO3XQBGD5Z4LWZXBKMD7GEM5AJ", "617361908edde0130fb9e2ed9b854c1fcc467409"});
        for (String[] pair : pairs) {
            assertTrue(net.derrek.bt4j.metainfo.MagnetUri.parse("magnet:?xt=urn:btih:" + pair[0]).infoHash()
                    .equals(net.derrek.bt4j.metainfo.MagnetUri.parse("magnet:?xt=urn:btih:" + pair[1]).infoHash()));
        }
    }
}
