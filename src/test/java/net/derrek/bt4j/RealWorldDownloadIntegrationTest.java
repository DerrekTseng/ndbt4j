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
 * 真實世界端到端（需網路，預設不執行）：
 * 使用者提供的無 tracker 磁力連結（doc/TEST-MAGNETS.md 第 2 組），
 * 純靠公共 DHT 找 peer → 取 metadata → 實際下載並以 SHA-1 驗證 piece。
 *
 * 為避免完整拉下整個檔案，跑到「至少一個 piece 通過驗證（progress > 0）」即停止——
 * 這已證明「連真實 peer → 請求 piece → 下載 → 驗證」的下載迴圈在真實網路可運作。
 *
 * 執行方式：{@code mvn test -Dbt4j.integration=true -Dtest=RealWorldDownloadIntegrationTest}
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

            // 等到至少一個 piece 通過 SHA-1 驗證（progress > 0），或整體完成
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
            assertFalse(task.state() == TaskState.ERROR, "session 不應進入 ERROR");
            assertTrue(task.progress() > 0.0,
                    "應至少有一個 piece 通過 SHA-1 驗證（下載迴圈在真實 swarm 運作）");

            bt.stop(task); // 證明完成，提前停止（不拉下整個檔案）
        }
    }

    @Test
    void bothTestMagnetPairsShareInfoHash() {
        // 額外健全性檢查（不需網路）：使用者兩組連結各自的 Base32 與 hex 為同一 hash
        List<String[]> pairs = List.of(
                new String[] {"IF4ZTTPVIENGKIVL5M2MEBMUGSTJ2GCU", "417999cdf5411a6522abeb34c2059434a69d1854"},
                new String[] {"MFZWDEEO3XQBGD5Z4LWZXBKMD7GEM5AJ", "617361908edde0130fb9e2ed9b854c1fcc467409"});
        for (String[] pair : pairs) {
            assertTrue(net.derrek.bt4j.metainfo.MagnetUri.parse("magnet:?xt=urn:btih:" + pair[0]).infoHash()
                    .equals(net.derrek.bt4j.metainfo.MagnetUri.parse("magnet:?xt=urn:btih:" + pair[1]).infoHash()));
        }
    }
}
