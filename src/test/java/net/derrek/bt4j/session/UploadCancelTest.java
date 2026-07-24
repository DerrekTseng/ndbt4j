package net.derrek.bt4j.session;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
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
 * Incoming Cancel (BEP 3) must actually drop still-queued upload work. Requests are queued for a per-peer
 * uploader thread rather than served inline, so a Cancel that arrives before the uploader reaches a request
 * removes it and saves the upload bandwidth entirely.
 */
class UploadCancelTest {

    private static final int PIECE_LENGTH = 16384;

    @Test
    void cancelDropsStillQueuedUploads(@TempDir Path tmp) throws Exception {
        int pieces = 40;
        byte[] content = TorrentFixtures.randomBytes(pieces * PIECE_LENGTH, 0xCA);
        Metainfo meta = TorrentFixtures.singleFile("up.bin", content, PIECE_LENGTH, "http://placeholder/");

        Path seederDir = tmp.resolve("seed");
        Files.createDirectories(seederDir);
        Files.write(seederDir.resolve(meta.name()), content);
        Bitfield all = new Bitfield(meta.pieceCount());
        all.setAll();
        ResumeData resume = new ResumeData(meta.toTorrentBytes(), all, Set.of(), seederDir, 0, false, true);

        // Throttle uploads so the uploader thread cannot drain the queue before our cancels land.
        try (BtClient seeder = BtClient.builder().listenPort(0).dhtEnabled(false)
                .uploadRateLimit(32 * 1024).maxPeersPerTorrent(5).build()) {
            seeder.restore(resume);

            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", seeder.listenPort()), 5000);
                socket.setSoTimeout(3000);
                DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

                out.write(Handshake.outgoing(meta.infoHash(), PeerId.generate(), false, true, false).encode());
                out.flush();
                in.readNBytes(Handshake.LENGTH);
                PeerMessage.write(out, new PeerMessage.Interested());

                // wait until the seeder unchokes us
                boolean unchoked = false;
                for (int i = 0; i < 40 && !unchoked; i++) {
                    if (PeerMessage.read(in, meta.pieceCount()) instanceof PeerMessage.Unchoke) {
                        unchoked = true;
                    }
                }
                assertTrue(unchoked, "seeder should unchoke an interested peer");

                // Ask for every piece, then immediately cancel all but the first. The cancels arrive while most
                // requests are still sitting in the upload queue, so those must never be served.
                for (int p = 0; p < pieces; p++) {
                    PeerMessage.write(out, new PeerMessage.Request(p, 0, PIECE_LENGTH));
                }
                for (int p = 1; p < pieces; p++) {
                    PeerMessage.write(out, new PeerMessage.Cancel(p, 0, PIECE_LENGTH));
                }
                out.flush();

                int piecesReceived = 0;
                try {
                    while (true) {
                        if (PeerMessage.read(in, meta.pieceCount()) instanceof PeerMessage.Piece) {
                            piecesReceived++;
                        }
                    }
                } catch (Exception expectedTimeout) {
                    // the read timeout ends the test: nothing further is being sent
                }
                assertTrue(piecesReceived < pieces,
                        "cancelled requests must not all be served, got " + piecesReceived + " of " + pieces);
            }
        }
    }
}
