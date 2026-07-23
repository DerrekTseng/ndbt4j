package net.derrek.bt4j.metainfo;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;

/**
 * A torrent's identifier: the SHA-1 of the raw bytes of the info dictionary (20 bytes, BitTorrent v1).
 */
public record InfoHash(byte[] bytes) {

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    public InfoHash {
        if (bytes.length != 20) {
            throw new IllegalArgumentException("info-hash must be 20 bytes, got " + bytes.length);
        }
    }

    /** 40-character lowercase hex, the common form following xt=urn:btih: in a magnet link. */
    public String hex() {
        return HexFormat.of().formatHex(bytes);
    }

    /** Parse 40-character hex (case-insensitive). */
    public static InfoHash fromHex(String hex40) {
        if (hex40.length() != 40) {
            throw new IllegalArgumentException("hex info-hash must be 40 characters, got " + hex40.length() + ": " + hex40);
        }
        return new InfoHash(HexFormat.of().parseHex(hex40));
    }

    /** Parse 32-character Base32 (RFC 4648, commonly used by older magnet links). */
    public static InfoHash fromBase32(String base32) {
        if (base32.length() != 32) {
            throw new IllegalArgumentException("Base32 info-hash must be 32 characters, got " + base32.length() + ": " + base32);
        }
        byte[] out = new byte[20];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (char c : base32.toUpperCase(Locale.ROOT).toCharArray()) {
            int value = BASE32_ALPHABET.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("illegal Base32 character: '" + c + "'");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out[index++] = (byte) (buffer >> (bits - 8));
                bits -= 8;
            }
        }
        return new InfoHash(out);
    }

    /** Compute the SHA-1 of the raw bytes of the info dictionary. */
    public static InfoHash ofInfoDict(byte[] infoDictBytes) {
        try {
            return new InfoHash(MessageDigest.getInstance("SHA-1").digest(infoDictBytes));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("the JDK always ships SHA-1", e);
        }
    }

    // records default to reference comparison for byte[]; must compare by content (InfoHash is used as a Map key)
    @Override
    public boolean equals(Object o) {
        return o instanceof InfoHash other && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "InfoHash[" + hex() + "]";
    }
}
