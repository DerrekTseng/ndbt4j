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

/** PEX（BEP 11）wire 格式 build/parse 往返，及 onMessage 的寬容性。 */
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

    /** 未 start 的連線，只作為 onMessage 的 address 來源。 */
    private static PeerConnection dummyConn() {
        return PeerConnection.outgoing(
                new PeerAddress(InetSocketAddress.createUnresolved("127.0.0.1", 1)),
                HASH, PeerId.generate(), 4, false, NOOP);
    }

    private static PeerAddress peer(String host, int port) {
        return new PeerAddress(new InetSocketAddress(host, port));
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

        receiver.onMessage(conn, registry, "not-bencoding".getBytes());     // 非 bencoding
        receiver.onMessage(conn, registry, "le".getBytes());                // 非 dictionary
        receiver.onMessage(conn, registry, "d5:added5:AAAAAe".getBytes());  // added 非 6 的倍數
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
