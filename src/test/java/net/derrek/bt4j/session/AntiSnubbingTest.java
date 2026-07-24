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
 * Anti-snubbing: one seeder unchokes and accepts requests but never sends any block (snubs us).
 * With anti-snubbing the leecher abandons the stuck blocks after the snub timeout and redistributes
 * them to a responsive seeder, completing well before the connection read timeout (150s).
 */
class AntiSnubbingTest {

    private static final int PIECE_LENGTH = 16384;

    /** Seeder that advertises a full bitfield and unchokes, but never answers a Request. */
    private static final class StallingSeeder implements AutoCloseable {
        private final ServerSocket server;
        private final Metainfo meta;
        private final ConcurrentLinkedQueue<Socket> sockets = new ConcurrentLinkedQueue<>();
        private volatile boolean closed;

        StallingSeeder(Metainfo meta) throws IOException {
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
                    // Requests are deliberately ignored -> we are snubbing the leecher.
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

    /** Fake tracker returning two compact peers (both seeder ports). */
    private static HttpServer twoPeerTracker(int portA, int portB) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/announce", exchange -> {
            byte[] peers = {
                    127, 0, 0, 1, (byte) (portA >> 8), (byte) portA,
                    127, 0, 0, 1, (byte) (portB >> 8), (byte) portB};
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
    void snubbingPeerIsAbandonedAndDownloadCompletes(@TempDir Path tmp) throws Exception {
        byte[] content = TorrentFixtures.randomBytes(60_000, 909); // 4 pieces
        Metainfo seederMeta = TorrentFixtures.singleFile("snub.bin", content, PIECE_LENGTH, "http://placeholder/");

        try (TestSeeder good = new TestSeeder(seederMeta, content);
             StallingSeeder bad = new StallingSeeder(seederMeta)) {
            HttpServer tracker = twoPeerTracker(bad.port(), good.port());
            try {
                String announce = "http://127.0.0.1:" + tracker.getAddress().getPort() + "/announce";
                Metainfo meta = TorrentFixtures.singleFile("snub.bin", content, PIECE_LENGTH, announce);

                try (BtClient client = BtClient.builder().lsdEnabled(false).listenPort(0).dhtEnabled(false).maxPeersPerTorrent(5).build()) {
                    DefaultTorrentSession session = (DefaultTorrentSession) client.addTorrent(meta);
                    // Shorten timings so a snub is detected in ~1s instead of 60s (request timeout left long here).
                    session.setChokeTimingForTest(1000, 1_000_000_000L, 30_000_000_000L);

                    CountDownLatch done = new CountDownLatch(1);
                    session.addListener(new SessionListener() {
                        @Override
                        public void onDownloadCompleted(TorrentSession s) {
                            done.countDown();
                        }
                    });
                    session.start(DownloadPlan.allFiles(tmp));

                    // Well under the 150s read timeout: proves stuck blocks were redistributed, not waited out.
                    assertTrue(done.await(30, TimeUnit.SECONDS),
                            "download should complete despite a snubbing peer");
                    assertArrayEquals(content, Files.readAllBytes(tmp.resolve("snub.bin")));
                }
            } finally {
                tracker.stop(0);
            }
        }
    }
}
