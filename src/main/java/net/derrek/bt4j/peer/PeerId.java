package net.derrek.bt4j.peer;

import java.util.Arrays;

/** 20-byte peer id（BEP 20 Azureus 風格：本套件前綴 "-ND1000-" + 12 隨機 bytes）。 */
public record PeerId(byte[] bytes) {

    public static final String CLIENT_PREFIX = "-ND1000-";

    public PeerId {
        if (bytes.length != 20) {
            throw new IllegalArgumentException("peer id 必須是 20 bytes");
        }
    }

    /** 產生本機 peer id（每個 BtClient 實例一個，跨 torrent 共用）。 */
    public static PeerId generate() {
        throw new UnsupportedOperationException("尚未實作");
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PeerId other && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
