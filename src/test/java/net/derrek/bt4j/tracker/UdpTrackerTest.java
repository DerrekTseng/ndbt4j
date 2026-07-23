package net.derrek.bt4j.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import net.derrek.bt4j.metainfo.InfoHash;
import net.derrek.bt4j.peer.PeerId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class UdpTrackerTest {

    private static final long MAGIC = 0x41727101980L;
    private static final long CONNECTION_ID = 0x1122334455667788L;

    private FakeUdpTracker fake;

    /** 假 UDP tracker：依 BEP 15 回應 connect / announce，行為可設定。 */
    private final class FakeUdpTracker implements AutoCloseable {

        final DatagramSocket socket = new DatagramSocket();
        final AtomicInteger announcesReceived = new AtomicInteger();
        volatile int dropFirstN;           // 忽略前 N 個封包（測重送）
        volatile String errorMessage;      // 非 null 時 announce 回 error
        volatile int lastEventCode = -1;

        FakeUdpTracker() throws IOException {
            Thread.ofVirtual().name("fake-udp-tracker").start(this::loop);
        }

        URI uri() {
            return URI.create("udp://127.0.0.1:" + socket.getLocalPort() + "/announce");
        }

        private void loop() {
            byte[] buffer = new byte[1024];
            try {
                while (true) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    if (dropFirstN > 0) {
                        dropFirstN--;
                        continue;
                    }
                    ByteBuffer in = ByteBuffer.wrap(buffer, 0, packet.getLength());
                    ByteBuffer out;
                    if (packet.getLength() == 16 && in.getLong(0) == MAGIC && in.getInt(8) == 0) {
                        // connect
                        out = ByteBuffer.allocate(16);
                        out.putInt(0).putInt(in.getInt(12)).putLong(CONNECTION_ID);
                    } else if (packet.getLength() == 98 && in.getInt(8) == 1) {
                        // announce
                        announcesReceived.incrementAndGet();
                        lastEventCode = in.getInt(80);
                        int transactionId = in.getInt(12);
                        if (errorMessage != null) {
                            byte[] msg = errorMessage.getBytes(StandardCharsets.UTF_8);
                            out = ByteBuffer.allocate(8 + msg.length);
                            out.putInt(3).putInt(transactionId).put(msg);
                        } else {
                            out = ByteBuffer.allocate(20 + 6);
                            out.putInt(1).putInt(transactionId)
                                    .putInt(1800)  // interval
                                    .putInt(3)     // leechers
                                    .putInt(5)     // seeders
                                    .put(new byte[] {10, 0, 0, 7, 0x1A, (byte) 0xE1}); // 10.0.0.7:6881
                        }
                    } else {
                        continue;
                    }
                    socket.send(new DatagramPacket(out.array(), out.position() > 0 ? out.position() : out.limit(),
                            packet.getAddress(), packet.getPort()));
                }
            } catch (IOException ignored) {
                // socket closed
            }
        }

        @Override
        public void close() {
            socket.close();
        }
    }

    private static AnnounceRequest request(AnnounceEvent event) {
        return new AnnounceRequest(
                InfoHash.fromHex("417999cdf5411a6522abeb34c2059434a69d1854"),
                PeerId.generate(), 6881, 0, 0, 1000, event, 50);
    }

    @AfterEach
    void tearDown() {
        if (fake != null) {
            fake.close();
        }
    }

    @Test
    void connectThenAnnounceReturnsPeers() throws Exception {
        fake = new FakeUdpTracker();
        UdpTracker tracker = new UdpTracker(fake.uri(), new int[] {2000, 2000});

        AnnounceResponse response = tracker.announce(request(AnnounceEvent.STARTED));

        assertEquals(Duration.ofSeconds(1800), response.interval());
        assertEquals(5, response.seeders().orElseThrow());
        assertEquals(3, response.leechers().orElseThrow());
        assertEquals(1, response.peers().size());
        assertEquals("10.0.0.7", response.peers().getFirst().socketAddress().getAddress().getHostAddress());
        assertEquals(6881, response.peers().getFirst().socketAddress().getPort());
        assertEquals(2, fake.lastEventCode); // started = 2
    }

    @Test
    void connectionIdIsCachedAcrossAnnounces() throws Exception {
        fake = new FakeUdpTracker();
        UdpTracker tracker = new UdpTracker(fake.uri(), new int[] {2000, 2000});

        tracker.announce(request(AnnounceEvent.STARTED));
        tracker.announce(request(AnnounceEvent.NONE));
        assertEquals(2, fake.announcesReceived.get());
        assertEquals(0, fake.lastEventCode); // none = 0
    }

    @Test
    void retransmitsAfterTimeout() throws Exception {
        fake = new FakeUdpTracker();
        fake.dropFirstN = 1; // 第一個 connect 被吃掉
        UdpTracker tracker = new UdpTracker(fake.uri(), new int[] {300, 2000});

        AnnounceResponse response = tracker.announce(request(AnnounceEvent.STARTED));
        assertEquals(1, response.peers().size());
    }

    @Test
    void errorResponseBecomesTrackerException() throws Exception {
        fake = new FakeUdpTracker();
        fake.errorMessage = "torrent not registered";
        UdpTracker tracker = new UdpTracker(fake.uri(), new int[] {2000, 2000});

        TrackerException e = assertThrows(TrackerException.class,
                () -> tracker.announce(request(AnnounceEvent.STARTED)));
        assertTrue(e.getMessage().contains("torrent not registered"));
    }

    @Test
    void unresponsiveTrackerTimesOut() {
        UdpTracker tracker = new UdpTracker(URI.create("udp://127.0.0.1:1/announce"), new int[] {200, 200});
        assertThrows(TrackerException.class, () -> tracker.announce(request(AnnounceEvent.STARTED)));
    }

    @Test
    void trackerOfSupportsUdpScheme() {
        assertTrue(Tracker.of(URI.create("udp://t.example.com:6969/announce")) instanceof UdpTracker);
        assertThrows(IllegalArgumentException.class,
                () -> Tracker.of(URI.create("udp://no-port.example.com/announce")));
    }
}
