package net.derrek.bt4j.peer.ext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.derrek.bt4j.metainfo.InfoHash;
import net.derrek.bt4j.peer.Handshake;
import net.derrek.bt4j.peer.PeerAddress;
import net.derrek.bt4j.peer.PeerConnection;
import net.derrek.bt4j.peer.PeerId;
import net.derrek.bt4j.peer.PeerMessage;
import org.junit.jupiter.api.Test;

/** PEX (BEP 11) wire-format build/parse round trip, and the leniency of onMessage. */
class PeerExchangeTest {

    private static final InfoHash HASH = InfoHash.fromHex("417999cdf5411a6522abeb34c2059434a69d1854");

    private static final PeerConnection.Listener NOOP = new PeerConnection.Listener() {
        @Override
        public void onHandshakeCompleted(PeerConnection c, Handshake h) {
        }

        @Override
        public void onMessage(PeerConnection c, PeerMessage m) {
        }

        @Override
        public void onClosed(PeerConnection c, IOException e) {
        }
    };

    /** An unstarted connection, used only as the address source for onMessage. */
    private static PeerConnection dummyConn() {
        return PeerConnection.outgoing(
                new PeerAddress(InetSocketAddress.createUnresolved("127.0.0.1", 1)),
                HASH, PeerId.generate(), 4, false, NOOP);
    }

    private static PeerAddress peer(String host, int port) {
        return new PeerAddress(new InetSocketAddress(host, port));
    }

    @Test
    void droppedPeersAreReportedSeparatelyFromAdded() {
        List<PeerAddress> added = List.of(peer("10.0.0.1", 6881));
        List<PeerAddress> dropped = List.of(peer("10.0.0.9", 6882), peer("172.16.0.3", 6999));
        byte[] payload = PeerExchange.build(added, dropped);

        ConcurrentLinkedQueue<PeerAddress> discovered = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<PeerAddress> gone = new ConcurrentLinkedQueue<>();
        PeerExchange receiver = new PeerExchange(Set::of, discovered::addAll, gone::addAll);
        receiver.onMessage(dummyConn(), new ExtensionRegistry(List.of(receiver)), payload);

        assertEquals(Set.copyOf(added), Set.copyOf(discovered));
        assertEquals(Set.copyOf(dropped), Set.copyOf(gone), "dropped/dropped6 entries should be surfaced");
    }

    @Test
    void buildThenParseRoundTripIpv4() {
        List<PeerAddress> added = List.of(peer("10.0.0.1", 6881), peer("192.168.1.5", 51413));
        byte[] payload = PeerExchange.build(added, List.of());

        ConcurrentLinkedQueue<PeerAddress> discovered = new ConcurrentLinkedQueue<>();
        PeerExchange receiver = new PeerExchange(Set::of, discovered::addAll);
        receiver.onMessage(dummyConn(), new ExtensionRegistry(List.of(receiver)), payload);

        assertEquals(Set.copyOf(added), Set.copyOf(discovered));
    }

    @Test
    void buildIncludesIpv6InAdded6() {
        List<PeerAddress> added = List.of(peer("10.0.0.1", 6881), peer("2001:db8::1", 6881));
        byte[] payload = PeerExchange.build(added, List.of());

        ConcurrentLinkedQueue<PeerAddress> discovered = new ConcurrentLinkedQueue<>();
        PeerExchange receiver = new PeerExchange(Set::of, discovered::addAll);
        receiver.onMessage(dummyConn(), new ExtensionRegistry(List.of(receiver)), payload);

        assertEquals(Set.copyOf(added), Set.copyOf(discovered));
    }

    @Test
    void malformedPayloadsAreIgnored() {
        ConcurrentLinkedQueue<PeerAddress> discovered = new ConcurrentLinkedQueue<>();
        PeerExchange receiver = new PeerExchange(Set::of, discovered::addAll);
        ExtensionRegistry registry = new ExtensionRegistry(List.of(receiver));
        PeerConnection conn = dummyConn();

        receiver.onMessage(conn, registry, "not-bencoding".getBytes());     // not bencoding
        receiver.onMessage(conn, registry, "le".getBytes());                // not a dictionary
        receiver.onMessage(conn, registry, "d5:added5:AAAAAe".getBytes());  // added not a multiple of 6
        assertTrue(discovered.isEmpty());
    }

    @Test
    void emptyPexMessageYieldsNoPeers() {
        byte[] payload = PeerExchange.build(List.of(), List.of());
        ConcurrentLinkedQueue<PeerAddress> discovered = new ConcurrentLinkedQueue<>();
        PeerExchange receiver = new PeerExchange(Set::of, discovered::addAll);
        receiver.onMessage(dummyConn(), new ExtensionRegistry(List.of(receiver)), payload);
        assertTrue(discovered.isEmpty());
    }
}
