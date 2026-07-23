package net.derrek.bt4j.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.derrek.bt4j.FakeHttpTracker;
import net.derrek.bt4j.TorrentFixtures;
import net.derrek.bt4j.metainfo.Metainfo;
import net.derrek.bt4j.peer.Handshake;
import net.derrek.bt4j.peer.PeerId;
import net.derrek.bt4j.peer.PeerMessage;
import net.derrek.bt4j.piece.Bitfield;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Per-request timeout: a seeder serves every block immediately except it silently drops the FIRST request
 * for one specific block (serving it on retry). The peer keeps delivering other blocks, so anti-snubbing
 * never fires; only re-queuing that individual timed-out request lets the download finish.
 */
class PerRequestTimeoutTest {

    private static final int PIECE_LENGTH = 16384;

    /** Seeder that drops the first request for piece 0/offset 0, serves everything else (and the retry) at once. */
    private static final class DropOneBlockSeeder implements AutoCloseable {
        private final ServerSocket server;
        private final Metainfo meta;
        private final byte[] content;
        private final ConcurrentLinkedQueue<Socket> sockets = new ConcurrentLinkedQueue<>();
        private final Set<Long> dropped = ConcurrentHashMap.newKeySet();
        private volatile boolean closed;

        DropOneBlockSeeder(Metainfo meta, byte[] content) throws IOException {
            this.meta = meta;
            this.content = content;
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
                long targetKey = 0L; // piece 0, begin 0
                while (!closed) {
                    PeerMessage message = PeerMessage.read(in, meta.pieceCount());
                    switch (message) {
                        case PeerMessage.Interested() -> PeerMessage.write(out, new PeerMessage.Unchoke());
                        case PeerMessage.Request(int piece, int begin, int length) -> {
                            long key = ((long) piece << 32) | (begin & 0xFFFFFFFFL);
                            if (key == targetKey && dropped.add(key)) {
                                // drop the first request for the target block (silent) -> needs a per-request-timeout retry
                            } else {
                                int start = piece * PIECE_LENGTH + begin;
                                PeerMessage.write(out, new PeerMessage.Piece(piece, begin,
                                        Arrays.copyOfRange(content, start, start + length)));
                            }
                        }
                        default -> {
                        }
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

    @Test
    void droppedBlockIsRetriedWithoutKillingTheConnection(@TempDir Path tmp) throws Exception {
        byte[] content = TorrentFixtures.randomBytes(60_000, 4242); // 4 pieces
        Metainfo seederMeta = TorrentFixtures.singleFile("req.bin", content, PIECE_LENGTH, "http://placeholder/");

        try (DropOneBlockSeeder seeder = new DropOneBlockSeeder(seederMeta, content);
             FakeHttpTracker tracker = new FakeHttpTracker(seeder.port())) {

            Metainfo meta = TorrentFixtures.singleFile("req.bin", content, PIECE_LENGTH, tracker.announceUrl());
            try (BtClient client = BtClient.builder().listenPort(0).dhtEnabled(false).maxPeersPerTorrent(5).build()) {
                DefaultTorrentSession session = (DefaultTorrentSession) client.addTorrent(meta);
                // Fast choke round + short request timeout; long snub timeout so ONLY the request timeout can help.
                session.setChokeTimingForTest(500, 60_000_000_000L, 1_000_000_000L);

                CountDownLatch done = new CountDownLatch(1);
                session.addListener(new SessionListener() {
                    @Override
                    public void onDownloadCompleted(TorrentSession s) {
                        done.countDown();
                    }
                });
                session.start(DownloadPlan.allFiles(tmp));

                assertTrue(done.await(20, TimeUnit.SECONDS),
                        "the single dropped block should be retried and the download complete");
                assertArrayEquals(content, Files.readAllBytes(tmp.resolve("req.bin")));
            }
        }
    }
}
