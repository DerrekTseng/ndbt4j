package net.derrek.bt4j.dht;

import java.security.SecureRandom;
import java.util.Arrays;
import net.derrek.bt4j.metainfo.InfoHash;

/** DHT node ID: 160-bit, in the same space as an info-hash (BEP 5, Kademlia XOR metric). */
public record NodeId(byte[] bytes) {

    private static final SecureRandom RANDOM = new SecureRandom();

    public NodeId {
        if (bytes.length != 20) {
            throw new IllegalArgumentException("node id must be 20 bytes");
        }
    }

    /** Generate a random node id (initial version; BEP 42's IP-binding rules are deferred). */
    public static NodeId random() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return new NodeId(bytes);
    }

    /** An info-hash and a node id are both 160-bit; convert between them during lookups. */
    public static NodeId of(InfoHash infoHash) {
        return new NodeId(infoHash.bytes());
    }

    /**
     * XOR distance comparison: with this as the target, which of a and b is closer.
     * Negative = a is closer; positive = b is closer; 0 = equidistant. Core of Kademlia routing.
     */
    public int compareDistance(NodeId a, NodeId b) {
        for (int i = 0; i < 20; i++) {
            int da = (a.bytes[i] ^ bytes[i]) & 0xFF;
            int db = (b.bytes[i] ^ bytes[i]) & 0xFF;
            if (da != db) {
                return Integer.compare(da, db);
            }
        }
        return 0;
    }

    /**
     * The highest differing bit position of two ids (159 = most significant bit; -1 = identical).
     * This is the Kademlia bucket index.
     */
    public static int highestDifferingBit(NodeId a, NodeId b) {
        for (int i = 0; i < 20; i++) {
            int xor = (a.bytes[i] ^ b.bytes[i]) & 0xFF;
            if (xor != 0) {
                return (19 - i) * 8 + (7 - Integer.numberOfLeadingZeros(xor) + 24);
            }
        }
        return -1;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NodeId other && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "NodeId[" + java.util.HexFormat.of().formatHex(bytes) + "]";
    }
}
