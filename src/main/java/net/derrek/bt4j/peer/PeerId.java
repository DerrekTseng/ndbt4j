package net.derrek.bt4j.peer;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/** 20-byte peer id (BEP 20 Azureus style: this library's prefix "-ND1000-" + 12 random characters). */
public record PeerId(byte[] bytes) {

    public static final String CLIENT_PREFIX = "-ND1000-";

    /** The random part uses printable characters for easy reading in logs and packet analysis. */
    private static final String RANDOM_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    public PeerId {
        if (bytes.length != 20) {
            throw new IllegalArgumentException("peer id must be 20 bytes, got " + bytes.length);
        }
    }

    /** Generates the local peer id (one per BtClient instance, shared across torrents). */
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
        // a peer id may contain arbitrary bytes (from the remote); non-printable characters shown as '.'
        StringBuilder sb = new StringBuilder(20);
        for (byte b : bytes) {
            sb.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
        }
        return "PeerId[" + sb + "]";
    }
}
