package net.derrek.bt4j.dht;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoutingTableTest {

    private static NodeId idWithFirstByte(int firstByte, int lastByte) {
        byte[] bytes = new byte[20];
        bytes[0] = (byte) firstByte;
        bytes[19] = (byte) lastByte;
        return new NodeId(bytes);
    }

    private static DhtNode node(NodeId id, int port) {
        return new DhtNode(id, InetSocketAddress.createUnresolved("10.0.0.1", port));
    }

    @Test
    void insertAndClosestOrdering() {
        NodeId self = idWithFirstByte(0x00, 0);
        RoutingTable table = new RoutingTable(self);
        NodeId near = idWithFirstByte(0x01, 1);   // very close to self
        NodeId far = idWithFirstByte(0x80, 2);    // top bit differs = farthest
        table.insert(node(near, 1));
        table.insert(node(far, 2));

        assertEquals(2, table.size());
        List<DhtNode> closest = table.closest(self, 2);
        assertEquals(near, closest.get(0).id());
        assertEquals(far, closest.get(1).id());
        assertEquals(1, table.closest(self, 1).size());
    }

    @Test
    void duplicateInsertUpdatesInsteadOfDuplicating() {
        NodeId self = idWithFirstByte(0x00, 0);
        RoutingTable table = new RoutingTable(self);
        NodeId id = idWithFirstByte(0x01, 1);
        table.insert(node(id, 1));
        table.insert(node(id, 1));
        assertEquals(1, table.size());
    }

    @Test
    void selfIsNeverInserted() {
        NodeId self = idWithFirstByte(0x00, 0);
        RoutingTable table = new RoutingTable(self);
        table.insert(node(self, 1));
        assertEquals(0, table.size());
    }

    @Test
    void bucketCapsAtKFreshNodes() {
        NodeId self = idWithFirstByte(0x00, 0);
        RoutingTable table = new RoutingTable(self);
        // 12 nodes in the same bucket (top bit all differs from self's bit 159)
        for (int i = 0; i < 12; i++) {
            table.insert(node(idWithFirstByte(0x80, i), i + 1));
        }
        assertEquals(RoutingTable.K, table.size()); // once full of fresh nodes, new ones are dropped
        assertTrue(table.closest(self, 20).size() == RoutingTable.K);
    }
}
