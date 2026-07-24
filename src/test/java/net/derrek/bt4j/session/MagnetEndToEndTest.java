package net.derrek.bt4j.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.derrek.bt4j.FakeHttpTracker;
import net.derrek.bt4j.TestSeeder;
import net.derrek.bt4j.TorrentFixtures;
import net.derrek.bt4j.metainfo.Metainfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Magnet-link end-to-end acceptance (M6):
 * addMagnet -> find peers via tracker/x.pe -> BEP 10 extension handshake -> fetch metadata via BEP 9 (SHA-1 verification)
 * -> METADATA_READY -> UI selection (select all here) -> download complete.
 */
class MagnetEndToEndTest {

    private static final int PIECE_LENGTH = 16384;

    @Test
    void magnetWithTrackerFullFlow(@TempDir Path tmp) throws Exception {
        byte[] content = TorrentFixtures.randomBytes(80_000, 77); // 5 pieces
        Metainfo source = TorrentFixtures.singleFile("magnet.bin", content, PIECE_LENGTH, "http://unused/");

        try (TestSeeder seeder = new TestSeeder(source, content);
             FakeHttpTracker tracker = new FakeHttpTracker(seeder.port())) {

            String magnet = "magnet:?xt=urn:btih:" + source.infoHash().hex()
                    + "&dn=magnet.bin"
                    + "&tr=" + URLEncoder.encode(tracker.announceUrl(), StandardCharsets.UTF_8);

            try (BtClient client = BtClient.builder().lsdEnabled(false).listenPort(6898).maxPeersPerTorrent(5)
                    .dhtEnabled(false).build()) {
                TorrentSession session = client.addMagnet(magnet);
                assertEquals(SessionState.FETCHING_METADATA, session.state());
                assertTrue(session.metadata().isEmpty());

                CountDownLatch metadataReady = new CountDownLatch(1);
                CountDownLatch completed = new CountDownLatch(1);
                session.addListener(new SessionListener() {
                    @Override
                    public void onMetadataReady(TorrentSession s, Metainfo meta) {
                        metadataReady.countDown();
                    }

                    @Override
                    public void onDownloadCompleted(TorrentSession s) {
                        completed.countDown();
                    }
                });

                // UI scenario: await metadata -> show file list -> select -> start download
                Metainfo fetched = session.awaitMetadata(Duration.ofSeconds(20));
                assertTrue(metadataReady.await(5, TimeUnit.SECONDS));
                assertEquals(SessionState.METADATA_READY, session.state());
                assertEquals("magnet.bin", fetched.name());
                assertEquals(source.infoHash(), fetched.infoHash());
                assertEquals(1, fetched.files().size());
                assertEquals(80_000, fetched.totalLength());
                assertArrayEquals(source.infoDictBytes(), fetched.infoDictBytes());

                // metadata obtained from the magnet can be exported as a .torrent (info-hash unchanged)
                Path exported = tmp.resolve("exported.torrent");
                fetched.saveTorrentFile(exported);
                assertEquals(source.infoHash(), Metainfo.parse(exported).infoHash());

                session.start(DownloadPlan.allFiles(tmp));
                assertTrue(completed.await(30, TimeUnit.SECONDS), "download did not complete within 30 seconds");
                assertEquals(SessionState.SEEDING, session.state());
            }
        }
        assertArrayEquals(content, Files.readAllBytes(tmp.resolve("magnet.bin")));
    }

    @Test
    void magnetWithDirectPeerNoTracker(@TempDir Path tmp) throws Exception {
        byte[] content = TorrentFixtures.randomBytes(40_000, 88); // 3 pieces
        Metainfo source = TorrentFixtures.singleFile("xpe.bin", content, PIECE_LENGTH, "http://unused/");

        try (TestSeeder seeder = new TestSeeder(source, content)) {
            // No tracker, relying solely on x.pe direct connection (simulating the trackerless scenario before M7)
            String magnet = "magnet:?xt=urn:btih:" + source.infoHash().hex()
                    + "&x.pe=127.0.0.1:" + seeder.port();

            try (BtClient client = BtClient.builder().lsdEnabled(false).listenPort(6897).dhtEnabled(false).build()) {
                TorrentSession session = client.addMagnet(magnet);
                Metainfo fetched = session.awaitMetadata(Duration.ofSeconds(20));
                assertEquals("xpe.bin", fetched.name());
                assertEquals(source.infoHash(), fetched.infoHash());
            }
        }
    }
}
