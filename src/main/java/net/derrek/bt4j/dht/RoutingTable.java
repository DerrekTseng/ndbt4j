package net.derrek.bt4j.dht;

import java.util.List;

/**
 * Kademlia 路由表（BEP 5）：以本機 node id 為中心的 bucket 樹，
 * 每個 bucket 最多 8 個節點，good/questionable/bad 狀態管理。
 */
public final class RoutingTable {

    public RoutingTable(NodeId self) {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** 節點回應過查詢（good）時加入或更新。bucket 滿時依 BEP 5 規則汰換。 */
    public void insert(DhtNode node) {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** 取離 target 最近的 K 個節點（K=8），供迭代查詢與 find_node 回應使用。 */
    public List<DhtNode> closest(NodeId target, int k) {
        throw new UnsupportedOperationException("尚未實作");
    }

    public int size() {
        throw new UnsupportedOperationException("尚未實作");
    }
}
