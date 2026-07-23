package net.derrek.bt4j.metainfo;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;

/**
 * torrent 的識別碼：info 字典原始位元組的 SHA-1（20 bytes，BitTorrent v1）。
 */
public record InfoHash(byte[] bytes) {

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    public InfoHash {
        if (bytes.length != 20) {
            throw new IllegalArgumentException("info-hash 必須是 20 bytes，收到 " + bytes.length);
        }
    }

    /** 40 字元小寫 hex，即磁力連結 xt=urn:btih: 後的常見形式。 */
    public String hex() {
        return HexFormat.of().formatHex(bytes);
    }

    /** 解析 40 字元 hex（大小寫皆可）。 */
    public static InfoHash fromHex(String hex40) {
        if (hex40.length() != 40) {
            throw new IllegalArgumentException("hex info-hash 必須是 40 字元，收到 " + hex40.length() + ": " + hex40);
        }
        return new InfoHash(HexFormat.of().parseHex(hex40));
    }

    /** 解析 32 字元 Base32（RFC 4648，舊版磁力連結常用此形式）。 */
    public static InfoHash fromBase32(String base32) {
        if (base32.length() != 32) {
            throw new IllegalArgumentException("Base32 info-hash 必須是 32 字元，收到 " + base32.length() + ": " + base32);
        }
        byte[] out = new byte[20];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (char c : base32.toUpperCase(Locale.ROOT).toCharArray()) {
            int value = BASE32_ALPHABET.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("非法的 Base32 字元: '" + c + "'");
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

    /** 對 info 字典的原始位元組計算 SHA-1。 */
    public static InfoHash ofInfoDict(byte[] infoDictBytes) {
        try {
            return new InfoHash(MessageDigest.getInstance("SHA-1").digest(infoDictBytes));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("JDK 必定內建 SHA-1", e);
        }
    }

    // record 對 byte[] 預設是參考比較，須以內容比較（InfoHash 會作為 Map key）
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
