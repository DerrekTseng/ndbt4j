package net.derrek.bt4j.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.derrek.bt4j.metainfo.InfoHash;
import net.derrek.bt4j.peer.PeerAddress;
import net.derrek.bt4j.peer.PeerId;
import org.junit.jupiter.api.Test;

class TrackerManagerTest {

    private static final PeerAddress PEER =
            new PeerAddress(InetSocketAddress.createUnresolved("10.0.0.9", 6881));

    /** 可程式化的 stub tracker。 */
    private static final class StubTracker implements Tracker {

        final String name;
        final boolean failing;
        final List<AnnounceEvent> events = new CopyOnWriteArrayList<>();
        final CountDownLatch announced = new CountDownLatch(1);

        StubTracker(String name, boolean failing) {
            this.name = name;
            this.failing = failing;
        }

        @Override
        public URI uri() {
            return URI.create("http://" + name + ".example.com/announce");
        }

        @Override
        public AnnounceResponse announce(AnnounceRequest request) throws TrackerException {
            events.add(request.event());
            announced.countDown();
            if (failing) {
                throw new TrackerException("stub 失敗: " + name);
            }
            return new AnnounceResponse(Duration.ofMinutes(30),
                    OptionalInt.of(1), OptionalInt.of(1), List.of(PEER));
        }
    }

    private static AnnounceRequest request(AnnounceEvent event) {
        return new AnnounceRequest(
                InfoHash.fromHex("417999cdf5411a6522abeb34c2059434a69d1854"),
                PeerId.generate(), 6881, 0, 0, 1000, event, 50);
    }

    @Test
    void firstAnnounceCarriesStartedAndDeliversPeers() throws Exception {
        StubTracker tracker = new StubTracker("t1", false);
        ConcurrentLinkedQueue<PeerAddress> found = new ConcurrentLinkedQueue<>();
        CountDownLatch delivered = new CountDownLatch(1);

        TrackerManager manager = new TrackerManager(
                List.of(List.of(tracker)),
                TrackerManagerTest::request,
                peers -> {
                    found.addAll(peers);
                    delivered.countDown();
                });
        manager.start();
        try {
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertEquals(AnnounceEvent.STARTED, tracker.events.getFirst());
            assertEquals(List.of(PEER), List.copyOf(found));
        } finally {
            manager.close();
        }
    }

    @Test
    void failingTrackerFallsThroughToNextInTier() throws Exception {
        StubTracker bad = new StubTracker("bad", true);
        StubTracker good = new StubTracker("good", false);
        CountDownLatch delivered = new CountDownLatch(1);

        // 單一 tier 兩個 tracker：TrackerManager 建構時會洗牌，
        // 但無論順序為何，最終必定輪到 good 成功
        TrackerManager manager = new TrackerManager(
                List.of(List.of(bad, good)),
                TrackerManagerTest::request,
                _ -> delivered.countDown());
        manager.start();
        try {
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertEquals(1, good.events.size());
        } finally {
            manager.close();
        }
    }

    @Test
    void completedAndStoppedEventsAreSent() throws Exception {
        StubTracker tracker = new StubTracker("t1", false);
        TrackerManager manager = new TrackerManager(
                List.of(List.of(tracker)), TrackerManagerTest::request, _ -> {
                });
        manager.start();
        assertTrue(tracker.announced.await(5, TimeUnit.SECONDS));

        manager.announceCompleted();
        waitUntil(() -> tracker.events.contains(AnnounceEvent.COMPLETED));

        manager.close();
        waitUntil(() -> tracker.events.contains(AnnounceEvent.STOPPED));

        assertEquals(AnnounceEvent.STARTED, tracker.events.getFirst());
        assertEquals(AnnounceEvent.STOPPED, tracker.events.getLast());
    }

    @Test
    void closeWithoutSuccessfulStartSkipsStoppedAnnounce() throws Exception {
        StubTracker failing = new StubTracker("bad", true);
        TrackerManager manager = new TrackerManager(
                List.of(List.of(failing)), TrackerManagerTest::request, _ -> {
                });
        manager.start();
        assertTrue(failing.announced.await(5, TimeUnit.SECONDS));
        manager.close();
        Thread.sleep(200);
        // 從未成功 announce（started 沒送達），關閉時不送 stopped
        assertTrue(failing.events.stream().noneMatch(e -> e == AnnounceEvent.STOPPED));
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("等候條件逾時");
            }
            Thread.sleep(20);
        }
    }
}
