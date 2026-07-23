package net.derrek.bt4j.dht;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 路由表中的一個遠端 DHT 節點。 */
public record DhtNode(NodeId id, InetSocketAddress address) {

    /** compact node info（BEP 5）：每 26 bytes = node id(20) + IPv4(4) + port(2)。 */
    public static List<DhtNode> fromCompact(byte[] compact) {
        List<DhtNode> result = new ArrayList<>(compact.length / 26);
        for (int i = 0; i + 26 <= compact.length; i += 26) {
            try {
                NodeId id = new NodeId(Arrays.copyOfRange(compact, i, i + 20));
                InetAddress ip = InetAddress.getByAddress(Arrays.copyOfRange(compact, i + 20, i + 24));
                int port = ((compact[i + 24] & 0xFF) << 8) | (compact[i + 25] & 0xFF);
                if (port > 0) {
                    result.add(new DhtNode(id, new InetSocketAddress(ip, port)));
                }
            } catch (UnknownHostException e) {
                throw new AssertionError("固定長度位址不可能失敗", e);
            }
        }
        return result;
    }

    /** 序列化為 compact node info。IPv6 位址略過（BEP 32 未支援）。 */
    public static byte[] toCompact(List<DhtNode> nodes) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (DhtNode node : nodes) {
            byte[] ip = node.address().getAddress() == null ? null : node.address().getAddress().getAddress();
            if (ip == null || ip.length != 4) {
                continue;
            }
            out.writeBytes(node.id().bytes());
            out.writeBytes(ip);
            out.write(node.address().getPort() >> 8);
            out.write(node.address().getPort() & 0xFF);
        }
        return out.toByteArray();
    }
}
