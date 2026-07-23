package net.derrek.bt4j.dht;

import java.util.ArrayList;
import java.util.List;

/**
 * Kademlia routing table (BEP 5): 160 buckets centered on the local node id,
 * each bucket holding at most {@value #K} nodes.
 * Eviction rule (simplified): already present -> refresh liveness; bucket not full -> add;
 * full -> replace the least-recently-seen node that has gone stale (15 minutes), otherwise drop the new node.
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

    /** Add or update a node when it has responded to a query (only nodes "proven alive" are stored). */
    public synchronized void insert(DhtNode node) {
        int bucketIndex = NodeId.highestDifferingBit(self, node.id());
        if (bucketIndex < 0) {
            return; // self
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
        // full: find a stale node to replace
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
        // all fresh: drop the new node (Kademlia prefers long-lived, proven nodes)
    }

    /** Get the k nodes closest to target, for iterative lookups and find_node responses. */
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
