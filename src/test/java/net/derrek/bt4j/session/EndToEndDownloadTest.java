package net.derrek.bt4j.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.derrek.bt4j.TorrentFixtures;
import net.derrek.bt4j.metainfo.FileEntry;
import net.derrek.bt4j.metainfo.Metainfo;
import net.derrek.bt4j.peer.Handshake;
import net.derrek.bt4j.peer.PeerId;
import net.derrek.bt4j.peer.PeerMessage;
import net.derrek.bt4j.piece.Bitfield;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 端到端驗收（M3）：本地假 tracker + 測試 seeder，
 * 完整走過 announce → compact peers → handshake → 下載 → SHA-1 驗證 → 檔案落地。
 */
class EndToEndDownloadTest {

    private static final int PIECE_LENGTH = 16384;

    /** 最小可用 seeder：回應 handshake、送全滿 bitfield、Interested→Unchoke、Request→Piece。 */
    private static final class TestSeeder implements AutoCloseable {

        private final ServerSocket server;
        private final byte[] content;
        private final Metainfo metainfo;
        private final ConcurrentLinkedQueue<Socket> sockets = new ConcurrentLinkedQueue<>();
        private volatile boolean closed;

        TestSeeder(Metainfo metainfo, byte[] content) throws IOException {
            this.metainfo = metainfo;
            this.content = content;
            this.server = new ServerSocket(0);
            Thread.ofVirtual().name("test-seeder-accept").start(this::acceptLoop);
        }

        int port() {
            return server.getLocalPort();
        }

        private void acceptLoop() {
            try {
                while (!closed) {
                    Socket socket = server.accept();
                    sockets.add(socket);
                    Thread.ofVirtual().name("test-seeder-conn").start(() -> serve(socket));
                }
            } catch (IOException ignored) {
                // server closed
            }
        }

        private void serve(Socket socket) {
            try (socket) {
                DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

                byte[] theirHandshake = in.readNBytes(Handshake.LENGTH);
                Handshake theirs = Handshake.decode(theirHandshake);
                out.write(Handshake.outgoing(theirs.infoHash(), PeerId.generate(), false, false, false).encode());
                out.flush();

                Bitfield full = new Bitfield(metainfo.pieceCount());
                full.setAll();
                PeerMessage.write(out, new PeerMessage.BitfieldMessage(full));

                while (!closed) {
                    PeerMessage message = PeerMessage.read(in, metainfo.pieceCount());
                    switch (message) {
                        case PeerMessage.Interested() -> PeerMessage.write(out, new PeerMessage.Unchoke());
                        case PeerMessage.Request(int piece, int begin, int length) -> {
                            int start = piece * PIECE_LENGTH + begin;
                            PeerMessage.write(out, new PeerMessage.Piece(piece, begin,
                                    Arrays.copyOfRange(content, start, start + length)));
                        }
                        default -> {
                        }
                    }
                }
            } catch (IOException ignored) {
                // 對方斷線
            }
        }

        @Override
        public void close() throws IOException {
            closed = true;
            server.close();
            for (Socket socket : sockets) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** 假 HTTP tracker：對任何 announce 回覆 compact 格式的 seeder 位址。 */
    private static HttpServer fakeTracker(int seederPort) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/announce", exchange -> {
            byte[] peer = {127, 0, 0, 1, (byte) (seederPort >> 8), (byte) (seederPort & 0xFF)};
            byte[] body = concat(
                    "d8:intervali120e5:peers6:".getBytes(StandardCharsets.ISO_8859_1),
                    peer,
                    "e".getBytes(StandardCharsets.ISO_8859_1));
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        return server;
    }

    private static byte[] concat(byte[]... arrays) {
        var out = new java.io.ByteArrayOutputStream();
        for (byte[] a : arrays) {
            out.writeBytes(a);
        }
        return out.toByteArray();
    }

    @Test
    void downloadSingleFileTorrentEndToEnd(@TempDir Path tmp) throws Exception {
        byte[] content = TorrentFixtures.randomBytes(100_000, 42); // 7 pieces（最後一個 1696 bytes）
        // 先用暫定 announce 產生 metainfo 取得 info-hash，再回填真正的 tracker URL
        Metainfo bootstrap = TorrentFixtures.singleFile("e2e.bin", content, PIECE_LENGTH, "http://placeholder/");

        try (TestSeeder seeder = new TestSeeder(bootstrap, content)) {
            HttpServer tracker = fakeTracker(seeder.port());
            try {
                String announce = "http://127.0.0.1:" + tracker.getAddress().getPort() + "/announce";
                Metainfo metainfo = TorrentFixtures.singleFile("e2e.bin", content, PIECE_LENGTH, announce);
                assertEquals(bootstrap.infoHash(), metainfo.infoHash()); // announce 不影響 info-hash

                try (BtClient client = BtClient.builder().listenPort(6899).maxPeersPerTorrent(5).build()) {
                    TorrentSession session = client.addTorrent(metainfo);
                    assertEquals(SessionState.METADATA_READY, session.state());

                    CountDownLatch completed = new CountDownLatch(1);
                    CountDownLatch fileDone = new CountDownLatch(1);
                    session.addListener(new SessionListener() {
                        @Override
                        public void onDownloadCompleted(TorrentSession s) {
                            completed.countDown();
                        }

                        @Override
                        public void onFileCompleted(TorrentSession s, FileEntry file) {
                            fileDone.countDown();
                        }
                    });

                    session.start(DownloadPlan.allFiles(tmp));
                    assertTrue(completed.await(30, TimeUnit.SECONDS), "30 秒內未完成下載");
                    assertTrue(fileDone.await(5, TimeUnit.SECONDS), "onFileCompleted 未觸發");
                    assertEquals(SessionState.SEEDING, session.state());

                    TorrentStats stats = session.stats();
                    assertEquals(1.0, stats.progress(), 1e-9);
                    assertEquals(100_000, stats.downloadedBytes());
                }
            } finally {
                tracker.stop(0);
            }
        }
        assertArrayEquals(content, Files.readAllBytes(tmp.resolve("e2e.bin")));
    }
}
