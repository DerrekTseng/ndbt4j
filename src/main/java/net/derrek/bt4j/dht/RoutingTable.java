package net.derrek.bt4j.dht;

import java.util.ArrayList;
import java.util.List;

/**
 * Kademlia 路由表（BEP 5）：以本機 node id 為中心的 160 個 bucket，
 * 每個 bucket 最多 {@value #K} 個節點。
 * 汰換規則（簡化版）：已存在 → 更新活性；bucket 未滿 → 加入；
 * 滿了 → 取代最久未回應且已逾時（15 分鐘）的節點，否則丟棄新節點。
 */
public final class RoutingTable {

    public static final int K = 8;
    private static final long STALE_AFTER_MILLIS = 15 * 60_000;

    private static final class Entry {
        final DhtNode node;
        long lastSeen;

        Entry(DhtNode node, long lastSeen) {
            this.node = node;
            this.lastSeen = lastSeen;
        }
    }

    private final NodeId self;
    private final List<List<Entry>> buckets = new ArrayList<>(160);

    public RoutingTable(NodeId self) {
        this.self = self;
        for (int i = 0; i < 160; i++) {
            buckets.add(new ArrayList<>());
        }
    }

    /** 節點回應過查詢時加入或更新（只放「已證明活著」的節點）。 */
    public synchronized void insert(DhtNode node) {
        int bucketIndex = NodeId.highestDifferingBit(self, node.id());
        if (bucketIndex < 0) {
            return; // 自己
        }
        List<Entry> bucket = buckets.get(bucketIndex);
        long now = System.currentTimeMillis();
        for (Entry entry : bucket) {
            if (entry.node.id().equals(node.id())) {
                entry.lastSeen = now;
                return;
            }
        }
        if (bucket.size() < K) {
            bucket.add(new Entry(node, now));
            return;
        }
        // 滿了：找一個逾時節點取代
        Entry oldest = null;
        for (Entry entry : bucket) {
            if (now - entry.lastSeen > STALE_AFTER_MILLIS && (oldest == null || entry.lastSeen < oldest.lastSeen)) {
                oldest = entry;
            }
        }
        if (oldest != null) {
            bucket.remove(oldest);
            bucket.add(new Entry(node, now));
        }
        // 全都新鮮：丟棄新節點（Kademlia 偏好久經考驗的節點）
    }

    /** 取離 target 最近的 k 個節點，供迭代查詢與 find_node 回應使用。 */
    public synchronized List<DhtNode> closest(NodeId target, int k) {
        List<DhtNode> all = new ArrayList<>();
        for (List<Entry> bucket : buckets) {
            for (Entry entry : bucket) {
                all.add(entry.node);
            }
        }
        all.sort((a, b) -> target.compareDistance(a.id(), b.id()));
        return all.size() <= k ? all : List.copyOf(all.subList(0, k));
    }

    public synchronized int size() {
        int total = 0;
        for (List<Entry> bucket : buckets) {
            total += bucket.size();
        }
        return total;
    }
}
