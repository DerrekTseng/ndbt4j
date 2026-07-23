package net.derrek.bt4j.tracker;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.security.SecureRandom;
import net.derrek.bt4j.metainfo.InfoHash;
import net.derrek.bt4j.peer.PeerId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Integration test that requires network access; not run by default.
 * How to run: {@code mvn test -Dbt4j.integration=true}
 */
@EnabledIfSystemProperty(named = "bt4j.integration", matches = "true")
class HttpTrackerIntegrationTest {

    @Test
    void announceToPublicTracker() throws TrackerException {
        // random info-hash: only verifies the protocol round-trip (the tracker returns an empty peer list + interval for an unknown hash)
        byte[] randomHash = new byte[20];
        new SecureRandom().nextBytes(randomHash);

        HttpTracker tracker = new HttpTracker(URI.create("http://tracker.opentrackr.org:1337/announce"));
        AnnounceResponse response = tracker.announce(new AnnounceRequest(
                new InfoHash(randomHash), PeerId.generate(), 6881, 0, 0, 0, AnnounceEvent.STARTED, 10));

        assertTrue(response.interval().toSeconds() > 0, "interval should be positive");
        // for a random hash, peers is almost always empty, but not enforced (in case it collides with a real hash)
    }
}
