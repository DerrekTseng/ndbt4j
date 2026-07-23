package net.derrek.bt4j.dht;

import java.util.Arrays;
import net.derrek.bt4j.metainfo.InfoHash;

/** DHT 節點 ID：160-bit，與 info-hash 同空間（BEP 5，Kademlia）。 */
public record NodeId(byte[] bytes) {

    public NodeId {
        if (bytes.length != 20) {
            throw new IllegalArgumentException("node id 必須是 20 bytes");
        }
    }

    /** 產生隨機 node id（初版；BEP 42 的 IP 綁定規則留待後續）。 */
    public static NodeId random() {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** XOR 距離比較：this 與 a、b 誰比較近 target。Kademlia 路由核心。 */
    public int compareDistance(NodeId a, NodeId b) {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** info-hash 與 node id 同為 160-bit，查詢時互轉。 */
    public static NodeId of(InfoHash infoHash) {
        return new NodeId(infoHash.bytes());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NodeId other && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
