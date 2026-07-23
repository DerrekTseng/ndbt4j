package net.derrek.bt4j.metainfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class MagnetUriTest {

    // 使用者提供的兩條連結（doc/TEST-MAGNETS.md 第 1 組）：同一 info-hash 的 Base32 與 hex 形式
    private static final String MAGNET_BASE32 = "magnet:?xt=urn:btih:IF4ZTTPVIENGKIVL5M2MEBMUGSTJ2GCU";
    private static final String MAGNET_HEX = "magnet:?xt=urn:btih:417999cdf5411a6522abeb34c2059434a69d1854";

    @Test
    void base32AndHexFormsYieldSameInfoHash() {
        MagnetUri base32 = MagnetUri.parse(MAGNET_BASE32);
        MagnetUri hex = MagnetUri.parse(MAGNET_HEX);
        assertEquals(base32.infoHash(), hex.infoHash());
        assertEquals("417999cdf5411a6522abeb34c2059434a69d1854", base32.infoHash().hex());
    }

    @Test
    void minimalLinkHasNoOptionalParts() {
        MagnetUri uri = MagnetUri.parse(MAGNET_HEX);
        assertTrue(uri.displayName().isEmpty());
        assertTrue(uri.trackers().isEmpty());
        assertTrue(uri.peers().isEmpty());
    }

    @Test
    void fullLinkWithAllParameters() {
        MagnetUri uri = MagnetUri.parse(MAGNET_HEX
                + "&dn=My+File%20Name"
                + "&tr=udp%3A%2F%2Ftracker.example.com%3A6969%2Fannounce"
                + "&tr=https%3A%2F%2Fbackup.example.org%2Fannounce"
                + "&x.pe=192.168.1.10:51413"
                + "&x.pe=[2001:db8::1]:6881");
        assertEquals("My File Name", uri.displayName().orElseThrow());
        assertEquals(List.of(
                URI.create("udp://tracker.example.com:6969/announce"),
                URI.create("https://backup.example.org/announce")), uri.trackers());
        assertEquals(List.of(
                InetSocketAddress.createUnresolved("192.168.1.10", 51413),
                InetSocketAddress.createUnresolved("2001:db8::1", 6881)), uri.peers());
    }

    @Test
    void malformedOptionalEntriesAreSkippedNotFatal() {
        MagnetUri uri = MagnetUri.parse(MAGNET_HEX + "&x.pe=not-an-address&x.pe=host:99999&noequals");
        assertTrue(uri.peers().isEmpty());
    }

    @Test
    void rejectInvalidLinks() {
        assertThrows(IllegalArgumentException.class, () -> MagnetUri.parse("http://example.com"));
        assertThrows(IllegalArgumentException.class, () -> MagnetUri.parse("magnet:?dn=NoHash"));
        assertThrows(IllegalArgumentException.class, () -> MagnetUri.parse("magnet:?xt=urn:btih:tooshort"));
        // v2 的 btmh 不支援，只有 btmh 沒有 btih 視為缺 xt
        assertThrows(IllegalArgumentException.class, () -> MagnetUri.parse(
                "magnet:?xt=urn:btmh:1220caf1e1c30e81cb361b9ee167c4aa64228a7fa4dd8381b8f2685b3ab3ebcb23d6"));
    }
}
