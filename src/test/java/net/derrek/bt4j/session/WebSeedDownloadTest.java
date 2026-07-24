package net.derrek.bt4j.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.derrek.bt4j.FakeHttpTracker;
import net.derrek.bt4j.TorrentFixtures;
import net.derrek.bt4j.metainfo.Metainfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * BEP 19 web seed: a torrent whose only source is an HTTP mirror (no peers at all) must still download to
 * completion, fetching whole pieces over HTTP range requests and verifying them like any other source.
 */
class WebSeedDownloadTest {

    private static final int PIECE_LENGTH = 16384;

    /** Serves {@code content} for GET requests, honouring a single {@code Range: bytes=a-b} header (206). */
    private static HttpServer mirror(byte[] content, AtomicInteger rangeRequests) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                String range = exchange.getRequestHeaders().getFirst("Range");
                if (range == null || !range.startsWith("bytes=")) {
                    exchange.sendResponseHeaders(200, content.length);
                    exchange.getResponseBody().write(content);
                    return;
                }
                rangeRequests.incrementAndGet();
                String[] bounds = range.substring("bytes=".length()).split("-");
                int start = Integer.parseInt(bounds[0]);
                int end = Integer.parseInt(bounds[1]); // inclusive
                byte[] slice = Arrays.copyOfRange(content, start, end + 1);
                exchange.getResponseHeaders().add("Content-Range", "bytes " + start + "-" + end + "/" + content.length);
                exchange.sendResponseHeaders(206, slice.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(slice);
                }
            }
        });
        server.start();
        return server;
    }

    @Test
    void downloadsEntirelyFromTheWebSeedWithNoPeers(@TempDir Path tmp) throws Exception {
        byte[] content = TorrentFixtures.randomBytes(100_000, 0xB19); // ~7 pieces
        AtomicInteger rangeRequests = new AtomicInteger();

        HttpServer mirror = mirror(content, rangeRequests);
        // A tracker that returns no peers: the download can only come from the web seed.
        try (FakeHttpTracker tracker = new FakeHttpTracker(0)) {
            try {
                String base = "http://127.0.0.1:" + mirror.getAddress().getPort() + "/";
                Metainfo meta = TorrentFixtures.singleFileWithWebSeed(
                        "seed.bin", content, PIECE_LENGTH, tracker.announceUrl(), base + "seed.bin");

                Path dir = tmp.resolve("out");
                try (BtClient client = BtClient.builder().lsdEnabled(false).listenPort(0)
                        .dhtEnabled(false).maxPeersPerTorrent(5).build()) {
                    TorrentSession session = client.addTorrent(meta);
                    CountDownLatch done = new CountDownLatch(1);
                    session.addListener(new SessionListener() {
                        @Override
                        public void onDownloadCompleted(TorrentSession s) {
                            done.countDown();
                        }
                    });
                    session.start(DownloadPlan.allFiles(dir));

                    assertTrue(done.await(30, TimeUnit.SECONDS), "web-seed-only download should complete");
                    assertArrayEquals(content, Files.readAllBytes(dir.resolve("seed.bin")));
                    assertTrue(rangeRequests.get() >= meta.pieceCount(),
                            "each piece should have been fetched via a range request, got " + rangeRequests.get());
                }
            } finally {
                mirror.stop(0);
            }
        }
    }
}
