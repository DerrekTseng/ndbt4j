package net.derrek.bt4j.peer;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/** 20-byte peer id（BEP 20 Azureus 風格：本套件前綴 "-ND1000-" + 12 隨機字元）。 */
public record PeerId(byte[] bytes) {

    public static final String CLIENT_PREFIX = "-ND1000-";

    /** 隨機部分用可列印字元，方便 log 與封包分析時直接閱讀。 */
    private static final String RANDOM_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    public PeerId {
        if (bytes.length != 20) {
            throw new IllegalArgumentException("peer id 必須是 20 bytes，收到 " + bytes.length);
        }
    }

    /** 產生本機 peer id（每個 BtClient 實例一個，跨 torrent 共用）。 */
    public static PeerId generate() {
        byte[] bytes = new byte[20];
        byte[] prefix = CLIENT_PREFIX.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(prefix, 0, bytes, 0, prefix.length);
        for (int i = prefix.length; i < bytes.length; i++) {
            bytes[i] = (byte) RANDOM_ALPHABET.charAt(RANDOM.nextInt(RANDOM_ALPHABET.length()));
        }
        return new PeerId(bytes);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PeerId other && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        // peer id 可能含任意位元組（遠端的），非可列印字元以 '.' 顯示
        StringBuilder sb = new StringBuilder(20);
        for (byte b : bytes) {
            sb.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
        }
        return "PeerId[" + sb + "]";
    }
}
