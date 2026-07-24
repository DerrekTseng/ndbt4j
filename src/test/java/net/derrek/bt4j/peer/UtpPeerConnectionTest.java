package net.derrek.bt4j.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.derrek.bt4j.metainfo.InfoHash;
import net.derrek.bt4j.utp.UtpEndpoint;
import net.derrek.bt4j.utp.UtpSocket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The full BitTorrent peer protocol (handshake + messages) running over uTP through the transport abstraction:
 * proves PeerConnection is genuinely transport-agnostic and that uTP carries peer-wire traffic end to end.
 */
class UtpPeerConnectionTest {

    private static final InfoHash HASH = InfoHash.fromHex("417999cdf5411a6522abeb34c2059434a69d1854");
    private static final int PIECE_COUNT = 20;

    private record Captured(CountDownLatch handshaked, AtomicReference<PeerMessage> lastMessage, CountDownLatch gotMessage) {
        PeerConnection.Listener listener() {
            return new PeerConnection.Listener() {
                @Override
                public void onHandshakeCompleted(PeerConnection c, Handshake h) {
                    handshaked.countDown();
                }

                @Override
                public void onMessage(PeerConnection c, PeerMessage m) {
                    lastMessage.set(m);
                    gotMessage.countDown();
                }

                @Override
                public void onClosed(PeerConnection c, IOException e) {
                }
            };
        }
    }

    private static Captured captured() {
        return new Captured(new CountDownLatch(1), new AtomicReference<>(), new CountDownLatch(1));
    }

    @Test
    @Timeout(30)
    void peerHandshakeAndMessagesTravelOverUtp() throws Exception {
        try (UtpEndpoint server = UtpEndpoint.bind(0);
             UtpEndpoint client = UtpEndpoint.bind(0)) {

            PeerId serverId = PeerId.generate();
            PeerId clientId = PeerId.generate();
            Captured serverSide = captured();
            Captured clientSide = captured();

            // The server accepts a uTP connection, reads the incoming handshake, and hands off to PeerConnection.
            AtomicReference<PeerConnection> serverConn = new AtomicReference<>();
            Thread acceptor = Thread.ofVirtual().start(() -> {
                try {
                    UtpSocket sock = server.accept();
                    assertNotNull(sock);
                    UtpTransport transport = new UtpTransport(sock);
                    byte[] raw = transport.inputStream().readNBytes(Handshake.LENGTH);
                    Handshake theirs = Handshake.decode(raw);
                    PeerConnection conn = PeerConnection.incoming(new PeerAddress(sock.remote()), transport, theirs,
                            HASH, serverId, PIECE_COUNT, false, serverSide.listener());
                    serverConn.set(conn);
                    conn.start();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            PeerAddress serverAddress = new PeerAddress(new InetSocketAddress("127.0.0.1", server.localPort()));
            PeerConnection clientConn = PeerConnection.outgoing(serverAddress, HASH, clientId, PIECE_COUNT, false,
                    clientSide.listener(), addr -> UtpTransport.connect(client, addr));
            clientConn.start();

            assertTrue(clientSide.handshaked().await(15, TimeUnit.SECONDS), "client should complete the handshake");
            assertTrue(serverSide.handshaked().await(15, TimeUnit.SECONDS), "server should complete the handshake");
            acceptor.join(15_000);

            // A real peer message must survive the round trip over uTP.
            clientConn.send(new PeerMessage.Have(7));
            assertTrue(serverSide.gotMessage().await(15, TimeUnit.SECONDS), "server should receive the Have over uTP");
            assertEquals(new PeerMessage.Have(7), serverSide.lastMessage().get());

            clientConn.close();
            serverConn.get().close();
        }
    }
}
