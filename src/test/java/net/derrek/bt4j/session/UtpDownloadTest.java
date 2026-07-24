package net.derrek.bt4j.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.derrek.bt4j.FakeHttpTracker;
import net.derrek.bt4j.TorrentFixtures;
import net.derrek.bt4j.metainfo.Metainfo;
import net.derrek.bt4j.piece.Bitfield;
import net.derrek.bt4j.storage.ResumeData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end download between two bt4j clients with uTP enabled: exercises the whole uTP peer stack wired into
 * BtClient — the shared UDP port, the uTP accept loop, and the TCP/uTP racing connector — and must transfer the
 * file byte-for-byte.
 */
class UtpDownloadTest {

    private static final int PIECE_LENGTH = 16384;

    private static BtClient utpClient() {
        return BtClient.builder().listenPort(0).dhtEnabled(false).lsdEnabled(false)
                .utpEnabled(true).maxPeersPerTorrent(5).build();
    }

    @Test
    void twoUtpClientsTransferAFile(@TempDir Path tmp) throws Exception {
        byte[] content = TorrentFixtures.randomBytes(120_000, 0x11FA); // ~8 pieces
        Metainfo seederMeta = TorrentFixtures.singleFile("utp.bin", content, PIECE_LENGTH, "http://unused/");

        Path seederDir = tmp.resolve("seeder");
        Files.createDirectories(seederDir);
        Files.write(seederDir.resolve(seederMeta.name()), content);
        Bitfield complete = new Bitfield(seederMeta.pieceCount());
        complete.setAll();
        ResumeData resume = new ResumeData(seederMeta.toTorrentBytes(), complete, Set.of(), seederDir, 0, false, true);

        try (BtClient seeder = utpClient();
             FakeHttpTracker tracker = new FakeHttpTracker(seeder.listenPort())) {
            seeder.restore(resume);

            Metainfo meta = TorrentFixtures.singleFile("utp.bin", content, PIECE_LENGTH, tracker.announceUrl());
            Path leecherDir = tmp.resolve("leecher");
            try (BtClient leecher = utpClient()) {
                TorrentSession session = leecher.addTorrent(meta);
                CountDownLatch done = new CountDownLatch(1);
                session.addListener(new SessionListener() {
                    @Override
                    public void onDownloadCompleted(TorrentSession s) {
                        done.countDown();
                    }
                });
                session.start(DownloadPlan.allFiles(leecherDir));

                assertTrue(done.await(30, TimeUnit.SECONDS), "download should complete with uTP enabled");
                assertArrayEquals(content, Files.readAllBytes(leecherDir.resolve("utp.bin")));
            }
        }
    }
}
