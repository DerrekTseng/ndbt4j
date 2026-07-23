package net.derrek.bt4j.metainfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class InfoHashTest {

    // 使用者提供的測試 hash（doc/TEST-MAGNETS.md 第 1 組）
    private static final String HEX = "417999cdf5411a6522abeb34c2059434a69d1854";
    private static final String BASE32 = "IF4ZTTPVIENGKIVL5M2MEBMUGSTJ2GCU";

    @Test
    void hexRoundTrip() {
        assertEquals(HEX, InfoHash.fromHex(HEX).hex());
    }

    @Test
    void hexIsCaseInsensitive() {
        assertEquals(InfoHash.fromHex(HEX), InfoHash.fromHex(HEX.toUpperCase()));
    }

    @Test
    void base32DecodesToSameHashAsHex() {
        assertEquals(InfoHash.fromHex(HEX), InfoHash.fromBase32(BASE32));
        assertEquals(InfoHash.fromHex(HEX), InfoHash.fromBase32(BASE32.toLowerCase()));
    }

    @Test
    void sha1KnownVector() {
        // SHA-1("abc") 的標準測試向量（FIPS 180-2）
        assertEquals(InfoHash.fromHex("a9993e364706816aba3e25717850c26c9cd0d89d"),
                InfoHash.ofInfoDict("abc".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void equalsByContent() {
        assertEquals(InfoHash.fromHex(HEX), InfoHash.fromHex(HEX));
        assertNotEquals(InfoHash.fromHex(HEX), InfoHash.fromHex("0000000000000000000000000000000000000000"));
        assertEquals(InfoHash.fromHex(HEX).hashCode(), InfoHash.fromHex(HEX).hashCode());
    }

    @Test
    void rejectWrongLengths() {
        assertThrows(IllegalArgumentException.class, () -> new InfoHash(new byte[19]));
        assertThrows(IllegalArgumentException.class, () -> InfoHash.fromHex("abc"));
        assertThrows(IllegalArgumentException.class, () -> InfoHash.fromBase32("ABC"));
        assertThrows(IllegalArgumentException.class, () -> InfoHash.fromBase32("1".repeat(32))); // '1' 不在 Base32 字母表
    }
}
