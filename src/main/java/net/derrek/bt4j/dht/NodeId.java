package net.derrek.bt4j.dht;

import java.security.SecureRandom;
import java.util.Arrays;
import net.derrek.bt4j.metainfo.InfoHash;

/** DHT 節點 ID：160-bit，與 info-hash 同空間（BEP 5，Kademlia XOR metric）。 */
public record NodeId(byte[] bytes) {

    private static final SecureRandom RANDOM = new SecureRandom();

    public NodeId {
        if (bytes.length != 20) {
            throw new IllegalArgumentException("node id 必須是 20 bytes");
        }
    }

    /** 產生隨機 node id（初版；BEP 42 的 IP 綁定規則留待後續）。 */
    public static NodeId random() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return new NodeId(bytes);
    }

    /** info-hash 與 node id 同為 160-bit，查詢時互轉。 */
    public static NodeId of(InfoHash infoHash) {
        return new NodeId(infoHash.bytes());
    }

    /**
     * XOR 距離比較：以 this 為目標，a 與 b 誰較近。
     * 負值 = a 較近；正值 = b 較近；0 = 等距。Kademlia 路由核心。
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
     * 兩個 id 的最高相異位元位置（159 = 最高位；-1 = 完全相同）。
     * 即 Kademlia bucket index。
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
