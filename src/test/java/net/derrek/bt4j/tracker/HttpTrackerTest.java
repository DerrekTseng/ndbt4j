package net.derrek.bt4j.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import net.derrek.bt4j.metainfo.InfoHash;
import net.derrek.bt4j.peer.PeerId;
import org.junit.jupiter.api.Test;

/** HttpTracker 的離線測試：查詢字串編碼與回應解析（不打網路）。 */
class HttpTrackerTest {

    private static byte[] raw(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void percentEncodeKeepsUnreservedAndEncodesRest() {
        assertEquals("Az09-_.~", HttpTracker.percentEncode(raw("Az09-_.~")));
        assertEquals("%20%2F%3A%3F%26%3D", HttpTracker.percentEncode(raw(" /:?&=")));
        assertEquals("%00%FF%80", HttpTracker.percentEncode(new byte[] {0x00, (byte) 0xFF, (byte) 0x80}));
    }

    @Test
    void buildQueryContainsAllRequiredParameters() {
        AnnounceRequest request = new AnnounceRequest(
                InfoHash.fromHex("417999cdf5411a6522abeb34c2059434a69d1854"),
                PeerId.generate(), 6881, 100, 200, 300, AnnounceEvent.STARTED, 50);
        String query = HttpTracker.buildQuery(request);

        assertTrue(query.startsWith("info_hash=Ay%99%CD%F5A%1Ae%22%AB%EB4%C2%05%944%A6%9D%18T"),
                "info_hash 逐位元組編碼錯誤: " + query);
        assertTrue(query.contains("&port=6881"));
        assertTrue(query.contains("&uploaded=100"));
        assertTrue(query.contains("&downloaded=200"));
        assertTrue(query.contains("&left=300"));
        assertTrue(query.contains("&compact=1"));
        assertTrue(query.contains("&event=started"));
        assertTrue(query.contains("&numwant=50"));
    }

    @Test
    void regularAnnounceOmitsEventParameter() {
        AnnounceRequest request = new AnnounceRequest(
                InfoHash.fromHex("417999cdf5411a6522abeb34c2059434a69d1854"),
                PeerId.generate(), 6881, 0, 0, 0, AnnounceEvent.NONE, 50);
        assertTrue(!HttpTracker.buildQuery(request).contains("event="));
    }

    @Test
    void parseCompactResponse() throws TrackerException {
        // interval=1800、complete=5、incomplete=3、peers=192.168.1.10:51413
        byte[] body = raw("d8:completei5e10:incompletei3e8:intervali1800e5:peers6:" +
                new String(new byte[] {(byte) 192, (byte) 168, 1, 10, (byte) 0xC8, (byte) 0xD5},
                        StandardCharsets.ISO_8859_1) + "e");
        AnnounceResponse response = HttpTracker.parseResponse(body);

        assertEquals(Duration.ofSeconds(1800), response.interval());
        assertEquals(5, response.seeders().orElseThrow());
        assertEquals(3, response.leechers().orElseThrow());
        assertEquals(1, response.peers().size());
        assertEquals(51413, response.peers().getFirst().socketAddress().getPort());
    }

    @Test
    void parseDictionaryFormPeers() throws TrackerException {
        byte[] body = raw("d8:intervali900e5:peersld2:ip12:192.168.1.104:porti6881eeee");
        AnnounceResponse response = HttpTracker.parseResponse(body);

        assertEquals(1, response.peers().size());
        assertEquals("192.168.1.10", response.peers().getFirst().socketAddress().getHostString());
        assertEquals(6881, response.peers().getFirst().socketAddress().getPort());
    }

    @Test
    void missingIntervalFallsBackToDefault() throws TrackerException {
        AnnounceResponse response = HttpTracker.parseResponse(raw("d5:peers0:e"));
        assertEquals(Duration.ofMinutes(30), response.interval());
        assertTrue(response.peers().isEmpty());
        assertTrue(response.seeders().isEmpty());
    }

    @Test
    void failureReasonBecomesException() {
        TrackerException e = assertThrows(TrackerException.class,
                () -> HttpTracker.parseResponse(raw("d14:failure reason13:torrent not fe")));
        assertTrue(e.getMessage().contains("torrent not f"));
    }

    @Test
    void garbageResponseBecomesException() {
        assertThrows(TrackerException.class, () -> HttpTracker.parseResponse(raw("<html>error</html>")));
        assertThrows(TrackerException.class, () -> HttpTracker.parseResponse(raw("i42e")));
    }

    @Test
    void trackerOfSelectsImplementationByScheme() {
        assertTrue(Tracker.of(java.net.URI.create("http://t.example.com/announce")) instanceof HttpTracker);
        assertTrue(Tracker.of(java.net.URI.create("HTTPS://t.example.com/announce")) instanceof HttpTracker);
        assertThrows(IllegalArgumentException.class, () -> Tracker.of(java.net.URI.create("ws://t.example.com")));
    }
}
