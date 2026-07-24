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
import java.util.concurrent.atomic.AtomicInteger;
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
 * Endgame duplicate suppression: with the snub and per-request timeouts held very long, the ONLY way a block
 * assigned to a never-delivering peer can complete is the endgame re-request to a responsive peer. When that
 * responsive peer delivers such a block, the leecher must send a Cancel to the stalled peer to stop the
 * now-redundant transfer. This asserts the stalled peer actually receives Cancel messages.
 */
class EndgameCancelTest {

    private static final int PIECE_LENGTH = 16384;

    /** Unchokes and advertises a full bitfield, never answers a Request, but records every Cancel it receives. */
    private static final class CancelRecordingSeeder implements AutoCloseable {
        private final ServerSocket server;
        private final Metainfo meta;
        private final ConcurrentLinkedQueue<Socket> sockets = new ConcurrentLinkedQueue<>();
        final AtomicInteger cancelsReceived = new AtomicInteger();
        private volatile boolean closed;

        CancelRecordingSeeder(Metainfo meta) throws IOException {
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
                    switch (message) {
                        case PeerMessage.Interested() -> PeerMessage.write(out, new PeerMessage.Unchoke());
                        case PeerMessage.Cancel(int p, int b, int l) -> cancelsReceived.incrementAndGet();
                        default -> {
                        }
                        // Requests are deliberately never answered: their blocks can only complete via endgame.
                    }
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

    /** Fake tracker returning two compact peers. */
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
    void deliveredEndgameBlockCancelsTheStalledPeer(@TempDir Path tmp) throws Exception {
        // 40 single-block pieces: more than one peer's initial pipeline (>= MIN_PIPELINE 16), so BOTH peers are
        // assigned an initial share. The stalled peer's share never arrives and, in endgame, gets re-requested
        // from the good peer -- each such delivery must Cancel the stalled peer.
        byte[] content = TorrentFixtures.randomBytes(40 * PIECE_LENGTH, 0xE6);
        Metainfo seederMeta = TorrentFixtures.singleFile("eg.bin", content, PIECE_LENGTH, "http://placeholder/");

        try (TestSeeder good = new TestSeeder(seederMeta, content);
             CancelRecordingSeeder stalled = new CancelRecordingSeeder(seederMeta)) {
            HttpServer tracker = twoPeerTracker(stalled.port(), good.port());
            try {
                String announce = "http://127.0.0.1:" + tracker.getAddress().getPort() + "/announce";
                Metainfo meta = TorrentFixtures.singleFile("eg.bin", content, PIECE_LENGTH, announce);

                try (BtClient client = BtClient.builder().lsdEnabled(false).listenPort(0).dhtEnabled(false).maxPeersPerTorrent(5).build()) {
                    DefaultTorrentSession session = (DefaultTorrentSession) client.addTorrent(meta);
                    // Long snub AND request timeouts: neither can rescue the stalled peer's blocks, so completion
                    // is driven solely by endgame re-requests -- which is exactly what fires the cancels.
                    session.setChokeTimingForTest(200, 60_000_000_000L, 60_000_000_000L);

                    CountDownLatch done = new CountDownLatch(1);
                    session.addListener(new SessionListener() {
                        @Override
                        public void onDownloadCompleted(TorrentSession s) {
                            done.countDown();
                        }
                    });
                    session.start(DownloadPlan.allFiles(tmp));

                    assertTrue(done.await(30, TimeUnit.SECONDS), "download should complete via the good peer");
                    assertArrayEquals(content, Files.readAllBytes(tmp.resolve("eg.bin")));
                    assertTrue(stalled.cancelsReceived.get() > 0,
                            "the stalled peer should have received at least one endgame Cancel");
                }
            } finally {
                tracker.stop(0);
            }
        }
    }
}
