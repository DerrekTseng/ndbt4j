package net.derrek.bt4j.dht;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.derrek.bt4j.metainfo.InfoHash;
import net.derrek.bt4j.peer.PeerAddress;
import org.junit.jupiter.api.Test;

/** Three DhtClients form a small in-process DHT network, exercising both client-side and server-side logic. */
class DhtNetworkTest {

    private static InfoHash randomHash() {
        byte[] bytes = new byte[20];
        new SecureRandom().nextBytes(bytes);
        return new InfoHash(bytes);
    }

    private static InetSocketAddress local(DhtClient client) {
        return new InetSocketAddress("127.0.0.1", client.port());
    }

    @Test
    void announceThenFindPeersAcrossNodes() throws Exception {
        try (DhtClient hub = new DhtClient(0, List.of())) {
            hub.start();
            try (DhtClient announcer = new DhtClient(0, List.of(local(hub)));
                 DhtClient finder = new DhtClient(0, List.of(local(hub)))) {
                announcer.start();
                finder.start();
                assertTrue(announcer.awaitBootstrap(Duration.ofSeconds(10)));
                assertTrue(finder.awaitBootstrap(Duration.ofSeconds(10)));

                InfoHash hash = randomHash();
                // announcer declares it serves this torrent on TCP 7777 -> hub stores it
                announcer.announce(hash, 7777).get(15, TimeUnit.SECONDS);

                // finder locates announcer via hub
                List<PeerAddress> peers = finder.findPeers(hash).get(15, TimeUnit.SECONDS);
                assertEquals(1, peers.size());
                assertEquals("127.0.0.1", peers.getFirst().socketAddress().getAddress().getHostAddress());
                assertEquals(7777, peers.getFirst().socketAddress().getPort());
            }
        }
    }

    @Test
    void findPeersOnUnknownHashReturnsEmpty() throws Exception {
        try (DhtClient hub = new DhtClient(0, List.of())) {
            hub.start();
            try (DhtClient finder = new DhtClient(0, List.of(local(hub)))) {
                finder.start();
                assertTrue(finder.awaitBootstrap(Duration.ofSeconds(10)));
                List<PeerAddress> peers = finder.findPeers(randomHash()).get(15, TimeUnit.SECONDS);
                assertTrue(peers.isEmpty());
            }
        }
    }

    @Test
    void bootstrapPopulatesRoutingTables() throws Exception {
        try (DhtClient hub = new DhtClient(0, List.of())) {
            hub.start();
            try (DhtClient node = new DhtClient(0, List.of(local(hub)))) {
                node.start();
                assertTrue(node.awaitBootstrap(Duration.ofSeconds(10)));
                assertTrue(node.routingTableSize() >= 1, "after bootstrap the routing table should contain hub");
                // after receiving node's query, hub should also remember node (server-side table insertion)
                long deadline = System.currentTimeMillis() + 5000;
                while (hub.routingTableSize() == 0 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20);
                }
                assertTrue(hub.routingTableSize() >= 1, "hub should learn node from the query");
            }
        }
    }
}
