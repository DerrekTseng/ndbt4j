package net.derrek.bt4j.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Duration;
import net.derrek.bt4j.metainfo.InfoHash;
import net.derrek.bt4j.metainfo.Metainfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Real-world acceptance (requires network, disabled by default):
 * A trackerless magnet link supplied by the user (doc/TEST-MAGNETS.md set 2, a healthy swarm),
 * relying purely on the public DHT to find peers and fetching metadata via BEP 9 (without downloading the content itself).
 * How to run: {@code mvn test -Dbt4j.integration=true -Dtest=DhtRealWorldIntegrationTest}
 */
@EnabledIfSystemProperty(named = "bt4j.integration", matches = "true")
class DhtRealWorldIntegrationTest {

    private static final String MAGNET = "magnet:?xt=urn:btih:617361908edde0130fb9e2ed9b854c1fcc467409";

    @Test
    void fetchMetadataFromRealSwarmViaDht() throws Exception {
        try (BtClient client = BtClient.builder().lsdEnabled(false).listenPort(6895).build()) {
            TorrentSession session = client.addMagnet(MAGNET);
            Metainfo metadata = session.awaitMetadata(Duration.ofMinutes(3));

            assertEquals(InfoHash.fromHex("617361908edde0130fb9e2ed9b854c1fcc467409"), metadata.infoHash());
            assertFalse(metadata.name().isEmpty());
            assertFalse(metadata.files().isEmpty());
            System.out.println("[DHT integration test] got metadata: name=" + metadata.name()
                    + ", files=" + metadata.files().size()
                    + ", totalLength=" + metadata.totalLength());
        }
    }
}
