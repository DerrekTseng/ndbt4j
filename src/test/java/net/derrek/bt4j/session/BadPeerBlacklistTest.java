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
 * M9：壞 peer 黑名單。一個只送損毀資料的 seeder 會讓 piece 反覆驗證失敗，
 * 累積達門檻後被封鎖並斷線。
 */
class BadPeerBlacklistTest {

    private static final int PIECE_LENGTH = 16384;

    /** 惡意 seeder：宣稱擁有全部 piece，但 Request 一律回覆全 0 的損毀資料。 */
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
                                PeerMessage.write(out, new PeerMessage.Piece(piece, begin, new byte[length])); // 損毀
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
            try (BtClient leecher = BtClient.builder().listenPort(0).dhtEnabled(false).maxPeersPerTorrent(5).build()) {
                DefaultTorrentSession session = (DefaultTorrentSession) leecher.addTorrent(leecherMeta);
                session.start(DownloadPlan.allFiles(tmp));

                // 反覆收到損毀 piece → 累積 strike → 封鎖
                long deadline = System.currentTimeMillis() + 20_000;
                while (session.bannedPeerCount() == 0 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50);
                }
                assertTrue(session.bannedPeerCount() >= 1, "送損毀資料的 peer 應被封鎖");
                assertFalse(session.state() == SessionState.SEEDING, "不應誤判為下載完成");
            }
        }
    }
}
