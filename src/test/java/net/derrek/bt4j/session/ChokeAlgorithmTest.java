package net.derrek.bt4j.session;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.derrek.bt4j.TorrentFixtures;
import net.derrek.bt4j.metainfo.Metainfo;
import net.derrek.bt4j.peer.Handshake;
import net.derrek.bt4j.peer.PeerId;
import net.derrek.bt4j.peer.PeerMessage;
import net.derrek.bt4j.piece.Bitfield;
import net.derrek.bt4j.storage.ResumeData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * choke 演算法：一個 bt4j 做種端面對多個「只表示 Interested」的 leecher 時，
 * 同時被 unchoke 的數量不應超過上傳槽數（UPLOAD_SLOTS=4）。
 */
class ChokeAlgorithmTest {

    private static final int PIECE_LENGTH = 16384;

    /** 極簡 leecher：連上做種端、送 Interested，記錄是否收到 Unchoke。永不 request。 */
    private static final class NoisyLeecher implements Runnable {
        private final int port;
        private final Metainfo meta;
        final AtomicBoolean unchoked = new AtomicBoolean();
        volatile Socket socket;

        NoisyLeecher(int port, Metainfo meta) {
            this.port = port;
            this.meta = meta;
        }

        @Override
        public void run() {
            try {
                Socket s = new Socket("127.0.0.1", port);
                this.socket = s;
                s.setSoTimeout(20_000);
                DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
                out.write(Handshake.outgoing(meta.infoHash(), PeerId.generate(), false, true, false).encode());
                out.flush();
                in.readNBytes(Handshake.LENGTH); // 對方 handshake
                PeerMessage.write(out, new PeerMessage.Interested());
                while (!Thread.currentThread().isInterrupted()) {
                    PeerMessage msg = PeerMessage.read(in, meta.pieceCount());
                    if (msg instanceof PeerMessage.Unchoke) {
                        unchoked.set(true);   // 追蹤「當前」choke 狀態（後到的訊息覆蓋）
                    } else if (msg instanceof PeerMessage.Choke) {
                        unchoked.set(false);
                    }
                }
            } catch (IOException ignored) {
            }
        }

        void close() {
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException ignored) {
            }
        }
    }

    @Test
    void seederUnchokesAtMostUploadSlots(@TempDir Path tmp) throws Exception {
        byte[] content = TorrentFixtures.randomBytes(40_000, 500);
        Metainfo meta = TorrentFixtures.singleFile("choke.bin", content, PIECE_LENGTH, "http://unused/");
        Files.createDirectories(tmp);
        Files.write(tmp.resolve("choke.bin"), content);
        Bitfield full = new Bitfield(meta.pieceCount());
        full.setAll();
        ResumeData resume = new ResumeData(meta.toTorrentBytes(), full, Set.of(), tmp, 0, false, true);

        try (BtClient seeder = BtClient.builder().listenPort(0).dhtEnabled(false).maxPeersPerTorrent(20).build()) {
            seeder.restore(resume);
            int port = seeder.listenPort();

            int leecherCount = 10;
            var leechers = new java.util.ArrayList<NoisyLeecher>();
            var threads = new java.util.ArrayList<Thread>();
            for (int i = 0; i < leecherCount; i++) {
                NoisyLeecher l = new NoisyLeecher(port, meta);
                leechers.add(l);
                threads.add(Thread.ofVirtual().start(l));
            }

            // 給 choke 演算法時間（interest 觸發即時重評；數量受 UPLOAD_SLOTS 限制）
            long deadline = System.currentTimeMillis() + 8000;
            long unchokedCount;
            do {
                Thread.sleep(200);
                unchokedCount = leechers.stream().filter(l -> l.unchoked.get()).count();
            } while (unchokedCount == 0 && System.currentTimeMillis() < deadline);

            Thread.sleep(1500); // 讓狀態穩定
            unchokedCount = leechers.stream().filter(l -> l.unchoked.get()).count();

            leechers.forEach(NoisyLeecher::close);
            threads.forEach(Thread::interrupt);

            assertTrue(unchokedCount >= 1, "至少要 unchoke 一個 peer，實際 " + unchokedCount);
            assertTrue(unchokedCount <= 4, "同時 unchoke 數不應超過 UPLOAD_SLOTS=4，實際 " + unchokedCount);
        }
    }
}
