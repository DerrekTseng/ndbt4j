package net.derrek.bt4j.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.derrek.bt4j.TestSeeder;
import net.derrek.bt4j.TorrentFixtures;
import net.derrek.bt4j.metainfo.Metainfo;
import net.derrek.bt4j.peer.Handshake;
import net.derrek.bt4j.peer.PeerId;
import net.derrek.bt4j.peer.PeerMessage;
import net.derrek.bt4j.piece.Bitfield;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Peer churn: with a single connection slot occupied first by a peer that unchokes but never delivers a block,
 * the download can only finish if that idle slot is recycled for the freshly discovered good peer waiting in the
 * connector's backlog. The snub and per-request timeouts are held very long so churn is the ONLY mechanism that
 * can rescue the stalled download.
 */
class PeerChurnTest {

    private static final int PIECE_LENGTH = 16384;

    /** Advertises a full bitfield and unchokes, but never answers a Request (occupies the slot doing nothing). */
    private static final class IdleSeeder implements AutoCloseable {
        private final ServerSocket server;
        private final Metainfo meta;
        private final ConcurrentLinkedQueue<Socket> sockets = new ConcurrentLinkedQueue<>();
        private volatile boolean closed;

        IdleSeeder(Metainfo meta) throws IOException {
            this.meta = meta;
            this.server = new ServerSocket(0);
            Thread.ofVirtual().start(this::acceptLoop);
        }

        int port() {
            return server.getLocalPort();
        }

        private void acceptLoop() {
            try {
                while (!closed) {
                    Socket s = server.accept();
                    sockets.add(s);
                    Thread.ofVirtual().start(() -> serve(s));
                }
            } catch (IOException ignored) {
            }
        }

        private void serve(Socket socket) {
            try (socket) {
                DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                Handshake theirs = Handshake.decode(in.readNBytes(Handshake.LENGTH));
                out.write(Handshake.outgoing(theirs.infoHash(), PeerId.generate(), false, true, false).encode());
                out.flush();
                Bitfield full = new Bitfield(meta.pieceCount());
                full.setAll();
                PeerMessage.write(out, new PeerMessage.BitfieldMessage(full));
                while (!closed) {
                    PeerMessage message = PeerMessage.read(in, meta.pieceCount());
                    if (message instanceof PeerMessage.Interested) {
                        PeerMessage.write(out, new PeerMessage.Unchoke());
                    }
                    // Requests are deliberately never answered.
                }
            } catch (IOException ignored) {
            }
        }

        @Override
        public void close() throws IOException {
            closed = true;
            server.close();
            for (Socket s : sockets) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** Fake tracker returning two compact peers, idle first so it takes the only slot before the good peer. */
    private static HttpServer twoPeerTracker(int idlePort, int goodPort) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/announce", exchange -> {
            byte[] peers = {
                    127, 0, 0, 1, (byte) (idlePort >> 8), (byte) idlePort,
                    127, 0, 0, 1, (byte) (goodPort >> 8), (byte) goodPort};
            byte[] prefix = "d8:intervali120e5:peers12:".getBytes(StandardCharsets.ISO_8859_1);
            byte[] body = new byte[prefix.length + peers.length + 1];
            System.arraycopy(prefix, 0, body, 0, prefix.length);
            System.arraycopy(peers, 0, body, prefix.length, peers.length);
            body[body.length - 1] = 'e';
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        return server;
    }

    @Test
    void idleSlotIsRecycledForAFreshPeer(@TempDir Path tmp) throws Exception {
        byte[] content = TorrentFixtures.randomBytes(60_000, 0xC4); // 4 pieces
        Metainfo seederMeta = TorrentFixtures.singleFile("churn.bin", content, PIECE_LENGTH, "http://placeholder/");

        try (IdleSeeder idle = new IdleSeeder(seederMeta);
             TestSeeder good = new TestSeeder(seederMeta, content)) {
            HttpServer tracker = twoPeerTracker(idle.port(), good.port());
            try {
                String announce = "http://127.0.0.1:" + tracker.getAddress().getPort() + "/announce";
                Metainfo meta = TorrentFixtures.singleFile("churn.bin", content, PIECE_LENGTH, announce);

                // maxPeers=1: the idle peer must be evicted before the good peer can ever connect.
                try (BtClient client = BtClient.builder().listenPort(0).dhtEnabled(false).maxPeersPerTorrent(1).build()) {
                    DefaultTorrentSession session = (DefaultTorrentSession) client.addTorrent(meta);
                    // Fast choke rounds (grace = 3 rounds = 600ms); snub and request timeouts held long so neither
                    // can rescue the idle peer's blocks -- only peer churn can.
                    session.setChokeTimingForTest(200, 600_000_000_000L, 600_000_000_000L);

                    CountDownLatch done = new CountDownLatch(1);
                    session.addListener(new SessionListener() {
                        @Override
                        public void onDownloadCompleted(TorrentSession s) {
                            done.countDown();
                        }
                    });
                    session.start(DownloadPlan.allFiles(tmp));

                    assertTrue(done.await(30, TimeUnit.SECONDS),
                            "download should complete only after the idle peer's slot is recycled for the good peer");
                    assertArrayEquals(content, Files.readAllBytes(tmp.resolve("churn.bin")));
                }
            } finally {
                tracker.stop(0);
            }
        }
    }
}
