package net.derrek.bt4j.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
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
 * M9: bad-peer blacklist. A seeder that only sends corrupt data makes pieces
 * fail verification repeatedly; once the strike count reaches the threshold the
 * peer is banned and disconnected.
 */
class BadPeerBlacklistTest {

    private static final int PIECE_LENGTH = 16384;

    /** Malicious seeder: claims to have every piece, but always answers Request with all-zero corrupt data. */
    private static final class MaliciousSeeder implements AutoCloseable {
        private final ServerSocket server;
        private final Metainfo meta;
        private final ConcurrentLinkedQueue<Socket> sockets = new ConcurrentLinkedQueue<>();
        private volatile boolean closed;

        MaliciousSeeder(Metainfo meta) throws IOException {
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
                        case PeerMessage.Request(int piece, int begin, int length) ->
                                PeerMessage.write(out, new PeerMessage.Piece(piece, begin, new byte[length])); // corrupt
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
    void peerSendingCorruptPiecesGetsBanned(@TempDir Path tmp) throws Exception {
        byte[] content = TorrentFixtures.randomBytes(60_000, 123); // 4 pieces
        Metainfo meta = TorrentFixtures.singleFile("bad.bin", content, PIECE_LENGTH, "http://unused/");

        try (MaliciousSeeder seeder = new MaliciousSeeder(meta);
             FakeHttpTracker tracker = new FakeHttpTracker(seeder.port())) {

            Metainfo leecherMeta = TorrentFixtures.singleFile("bad.bin", content, PIECE_LENGTH, tracker.announceUrl());
            try (BtClient leecher = BtClient.builder().lsdEnabled(false).listenPort(0).dhtEnabled(false).maxPeersPerTorrent(5).build()) {
                DefaultTorrentSession session = (DefaultTorrentSession) leecher.addTorrent(leecherMeta);
                session.start(DownloadPlan.allFiles(tmp));

                // Repeatedly receiving corrupt pieces -> accumulate strikes -> ban
                long deadline = System.currentTimeMillis() + 20_000;
                while (session.bannedPeerCount() == 0 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50);
                }
                assertTrue(session.bannedPeerCount() >= 1, "peer sending corrupt data should be banned");
                assertFalse(session.state() == SessionState.SEEDING, "should not be mistaken for download completion");
            }
        }
    }
}
