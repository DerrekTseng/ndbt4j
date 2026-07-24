package net.derrek.bt4j.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.derrek.bt4j.FakeHttpTracker;
import net.derrek.bt4j.TestSeeder;
import net.derrek.bt4j.TorrentFixtures;
import net.derrek.bt4j.metainfo.Metainfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Mid-download re-selection: a running torrent can change which files it downloads. Expanding the selection
 * fetches the newly wanted file (flipping a completed torrent back from seeding to downloading and re-fetching
 * boundary pieces that were only partly on disk); the download directory cannot be changed this way.
 */
class MidDownloadReselectTest {

    private static final int PIECE_LENGTH = 16384;

    private static Metainfo twoFileTorrent(byte[] a, byte[] b, String announce) {
        return TorrentFixtures.multiFile("multi", List.of(
                new TorrentFixtures.TestFile(List.of("a.bin"), a),
                new TorrentFixtures.TestFile(List.of("b.bin"), b)), PIECE_LENGTH, announce);
    }

    private static void waitUntil(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("condition not met within " + timeoutMillis + "ms");
    }

    private static boolean fileReady(Path path, long expectedSize) {
        try {
            return Files.exists(path) && Files.size(path) == expectedSize;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void expandingSelectionMidDownloadFetchesTheNewFile(@TempDir Path tmp) throws Exception {
        // a.bin [0,30000), b.bin [30000,60000): piece 1 straddles both, so adding b.bin forces its refetch.
        byte[] a = TorrentFixtures.randomBytes(30000, 71);
        byte[] b = TorrentFixtures.randomBytes(30000, 72);
        byte[] flat = new byte[60000];
        System.arraycopy(a, 0, flat, 0, 30000);
        System.arraycopy(b, 0, flat, 30000, 30000);

        Metainfo seederMeta = twoFileTorrent(a, b, "http://placeholder/");
        try (TestSeeder seeder = new TestSeeder(seederMeta, flat);
             FakeHttpTracker tracker = new FakeHttpTracker(seeder.port())) {

            Metainfo meta = twoFileTorrent(a, b, tracker.announceUrl());
            Path dir = tmp.resolve("out");
            Path fileA = dir.resolve("multi").resolve("a.bin");
            Path fileB = dir.resolve("multi").resolve("b.bin");

            try (BtClient leecher = BtClient.builder().lsdEnabled(false).listenPort(0)
                    .dhtEnabled(false).maxPeersPerTorrent(5).build()) {
                TorrentSession session = leecher.addTorrent(meta);

                // start with only a.bin selected
                session.start(new DownloadPlan(dir, Set.of(0), true));
                waitUntil(() -> fileReady(fileA, a.length), 30_000);
                assertArrayEquals(a, Files.readAllBytes(fileA));
                assertFalse(Files.exists(fileB), "b.bin must not be created while it is unselected");

                // expand the selection to include b.bin: the torrent resumes and fetches it
                session.start(new DownloadPlan(dir, Set.of(0, 1), true));
                waitUntil(() -> fileReady(fileB, b.length), 30_000);
                assertArrayEquals(b, Files.readAllBytes(fileB));
                // a.bin survived the boundary piece being re-fetched
                assertArrayEquals(a, Files.readAllBytes(fileA));
            }
        }
    }

    @Test
    void changingTheDownloadDirectoryIsRejected(@TempDir Path tmp) throws Exception {
        byte[] a = TorrentFixtures.randomBytes(20000, 73);
        byte[] b = TorrentFixtures.randomBytes(20000, 74);
        byte[] flat = new byte[40000];
        System.arraycopy(a, 0, flat, 0, 20000);
        System.arraycopy(b, 0, flat, 20000, 20000);

        Metainfo seederMeta = twoFileTorrent(a, b, "http://placeholder/");
        try (TestSeeder seeder = new TestSeeder(seederMeta, flat);
             FakeHttpTracker tracker = new FakeHttpTracker(seeder.port())) {

            Metainfo meta = twoFileTorrent(a, b, tracker.announceUrl());
            Path dir = tmp.resolve("out");
            try (BtClient leecher = BtClient.builder().lsdEnabled(false).listenPort(0)
                    .dhtEnabled(false).maxPeersPerTorrent(5).build()) {
                TorrentSession session = leecher.addTorrent(meta);
                session.start(new DownloadPlan(dir, Set.of(0), true));
                waitUntil(() -> fileReady(dir.resolve("multi").resolve("a.bin"), a.length), 30_000);

                assertThrows(IllegalArgumentException.class,
                        () -> session.start(new DownloadPlan(tmp.resolve("elsewhere"), Set.of(0, 1), true)),
                        "changing the download directory of a running torrent must be rejected");
            }
        }
    }
}
