package net.derrek.bt4j.lsd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.derrek.bt4j.metainfo.InfoHash;
import org.junit.jupiter.api.Test;

/** BT-SEARCH wire format (BEP 14): build/parse round trip and leniency against malformed datagrams. */
class LocalServiceDiscoveryTest {

    private static final InfoHash HASH = InfoHash.fromHex("417999cdf5411a6522abeb34c2059434a69d1854");

    @Test
    void buildThenParseRoundTrip() {
        String message = LocalServiceDiscovery.buildAnnounce(HASH, 51413, "abc123");
        assertTrue(message.startsWith("BT-SEARCH * HTTP/1.1\r\n"));
        assertTrue(message.endsWith("\r\n\r\n"));

        LocalServiceDiscovery.Announce parsed = LocalServiceDiscovery.parse(message);
        assertNotNull(parsed);
        assertEquals(51413, parsed.port());
        assertEquals(java.util.List.of(HASH.hex()), parsed.infoHashes());
        assertEquals("abc123", parsed.cookie());
    }

    @Test
    void headerNamesAreCaseInsensitiveAndMultipleHashesParse() {
        String other = "0123456789abcdef0123456789abcdef01234567";
        String message = "BT-SEARCH * HTTP/1.1\r\n"
                + "HOST: 239.192.152.143:6771\r\n"
                + "PORT: 6881\r\n"
                + "INFOHASH: " + HASH.hex() + "\r\n"
                + "InfoHash: " + other + "\r\n"
                + "Cookie: xyz\r\n\r\n\r\n";
        LocalServiceDiscovery.Announce parsed = LocalServiceDiscovery.parse(message);
        assertNotNull(parsed);
        assertEquals(6881, parsed.port());
        assertEquals(java.util.List.of(HASH.hex(), other), parsed.infoHashes());
        assertEquals("xyz", parsed.cookie());
    }

    @Test
    void malformedDatagramsAreRejected() {
        assertNull(LocalServiceDiscovery.parse("GET / HTTP/1.1\r\nPort: 1\r\n\r\n"), "not a BT-SEARCH");
        assertNull(LocalServiceDiscovery.parse("BT-SEARCH * HTTP/1.1\r\nInfohash: " + HASH.hex() + "\r\n\r\n"),
                "missing Port");
        assertNull(LocalServiceDiscovery.parse("BT-SEARCH * HTTP/1.1\r\nPort: 6881\r\n\r\n"),
                "missing Infohash");
        assertNull(LocalServiceDiscovery.parse("BT-SEARCH * HTTP/1.1\r\nPort: 99999\r\nInfohash: " + HASH.hex() + "\r\n\r\n"),
                "port out of range");
        assertNull(LocalServiceDiscovery.parse("BT-SEARCH * HTTP/1.1\r\nPort: notanumber\r\nInfohash: " + HASH.hex() + "\r\n\r\n"),
                "non-numeric port");
        // a short info-hash is ignored, leaving no usable hashes
        assertNull(LocalServiceDiscovery.parse("BT-SEARCH * HTTP/1.1\r\nPort: 6881\r\nInfohash: dead\r\n\r\n"));
    }

    @Test
    void announceCarriesTheAdvertisedListenPort() {
        // the announced port must be the TCP port peers should dial, not the multicast port
        LocalServiceDiscovery.Announce parsed =
                LocalServiceDiscovery.parse(LocalServiceDiscovery.buildAnnounce(HASH, 6881, "c"));
        assertNotNull(parsed);
        assertEquals(6881, parsed.port());
        assertTrue(LocalServiceDiscovery.PORT != parsed.port() || 6881 == LocalServiceDiscovery.PORT);
    }
}
