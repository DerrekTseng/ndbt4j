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
import java.util.HashSet;
import java.util.Set;
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
 * M9 Fast Extension (BEP 6): the seeder first replies with RejectRequest for each block and only serves it on retry,
 * verifying that a leecher receiving RejectRequest re-queues the block and eventually completes the download.
 */
class FastExtensionTest {

    private static final int PIECE_LENGTH = 16384;

    /** Seeder: advertises the fast bit; for each (piece,begin) it rejects the first time and only serves the second time. */
    private static final class RejectingSeeder implements AutoCloseable {
        private final ServerSocket server;
        private final Metainfo meta;
        private final byte[] content;
        private final ConcurrentLinkedQueue<Socket> sockets = new ConcurrentLinkedQueue<>();
        private volatile boolean closed;

        RejectingSeeder(Metainfo meta, byte[] content) throws IOException {
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
            Set<Long> seen = new HashSet<>();
            try (socket) {
                DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                Handshake theirs = Handshake.decode(in.readNBytes(Handshake.LENGTH));
                // fast=true advertises the Fast Extension
                out.write(Handshake.outgoing(theirs.infoHash(), PeerId.generate(), false, true, true).encode());
                out.flush();
                Bitfield full = new Bitfield(meta.pieceCount());
                full.setAll();
                PeerMessage.write(out, new PeerMessage.HaveAll());
                while (!closed) {
                    PeerMessage message = PeerMessage.read(in, meta.pieceCount());
                    switch (message) {
                        case PeerMessage.Interested() -> PeerMessage.write(out, new PeerMessage.Unchoke());
                        case PeerMessage.Request(int piece, int begin, int length) -> {
                            long key = ((long) piece << 32) | (begin & 0xFFFFFFFFL);
                            if (seen.add(key)) {
                                PeerMessage.write(out, new PeerMessage.RejectRequest(piece, begin, length)); // reject on first request
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
    void downloadCompletesDespiteRejectRequests(@TempDir Path tmp) throws Exception {
        byte[] content = TorrentFixtures.randomBytes(50_000, 321); // 4 pieces
        Metainfo meta = TorrentFixtures.singleFile("fast.bin", content, PIECE_LENGTH, "http://unused/");

        try (RejectingSeeder seeder = new RejectingSeeder(meta, content);
             FakeHttpTracker tracker = new FakeHttpTracker(seeder.port())) {

            Metainfo leecherMeta = TorrentFixtures.singleFile("fast.bin", content, PIECE_LENGTH, tracker.announceUrl());
            try (BtClient leecher = BtClient.builder().lsdEnabled(false).listenPort(0).dhtEnabled(false).maxPeersPerTorrent(5).build()) {
                TorrentSession session = leecher.addTorrent(leecherMeta);
                CountDownLatch done = new CountDownLatch(1);
                session.addListener(new SessionListener() {
                    @Override
                    public void onDownloadCompleted(TorrentSession s) {
                        done.countDown();
                    }
                });
                session.start(DownloadPlan.allFiles(tmp));
                assertTrue(done.await(30, TimeUnit.SECONDS), "should retry after RejectRequest and complete the download");
                assertArrayEquals(content, Files.readAllBytes(tmp.resolve("fast.bin")));
            }
        }
    }
}
