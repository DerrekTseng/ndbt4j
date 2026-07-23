package net.derrek.bt4j.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Duration;
import net.derrek.bt4j.metainfo.InfoHash;
import net.derrek.bt4j.metainfo.Metainfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 真實世界驗收（需網路，預設不執行）：
 * 使用者提供的無 tracker 磁力連結（doc/TEST-MAGNETS.md 第 2 組，健康 swarm），
 * 純靠公共 DHT 找 peer 並以 BEP 9 取得 metadata（不下載內容本體）。
 * 執行方式：{@code mvn test -Dbt4j.integration=true -Dtest=DhtRealWorldIntegrationTest}
 */
@EnabledIfSystemProperty(named = "bt4j.integration", matches = "true")
class DhtRealWorldIntegrationTest {

    private static final String MAGNET = "magnet:?xt=urn:btih:617361908edde0130fb9e2ed9b854c1fcc467409";

    @Test
    void fetchMetadataFromRealSwarmViaDht() throws Exception {
        try (BtClient client = BtClient.builder().listenPort(6895).build()) {
            TorrentSession session = client.addMagnet(MAGNET);
            Metainfo metadata = session.awaitMetadata(Duration.ofMinutes(3));

            assertEquals(InfoHash.fromHex("617361908edde0130fb9e2ed9b854c1fcc467409"), metadata.infoHash());
            assertFalse(metadata.name().isEmpty());
            assertFalse(metadata.files().isEmpty());
            System.out.println("[DHT 整合測試] 取得 metadata: name=" + metadata.name()
                    + ", files=" + metadata.files().size()
                    + ", totalLength=" + metadata.totalLength());
        }
    }
}
