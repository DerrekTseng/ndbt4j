package net.derrek.bt4j.utp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** End-to-end uTP reliability: a byte stream must arrive intact over a clean link and over a lossy, reordering one. */
class UtpTransferTest {

    /** Reads {@code total} bytes from a stream, or throws if the stream ends early. */
    private static byte[] readFully(InputStream in, int total) throws IOException {
        byte[] out = new byte[total];
        int read = 0;
        while (read < total) {
            int n = in.read(out, read, total - read);
            if (n < 0) {
                throw new IOException("stream ended after " + read + " of " + total + " bytes");
            }
            read += n;
        }
        return out;
    }

    private void transfer(DatagramSocket socketA, DatagramSocket socketB, int size) throws Exception {
        byte[] payload = new byte[size];
        new Random(size).nextBytes(payload);

        try (UtpEndpoint a = new UtpEndpoint(socketA);
             UtpEndpoint b = new UtpEndpoint(socketB)) {

            AtomicReference<byte[]> received = new AtomicReference<>();
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread server = Thread.ofVirtual().start(() -> {
                try {
                    UtpSocket sock = b.accept();
                    assertNotNull(sock);
                    received.set(readFully(sock.getInputStream(), size));
                } catch (Throwable t) {
                    error.set(t);
                }
            });

            UtpSocket client = a.connect(new InetSocketAddress("127.0.0.1", socketB.getLocalPort()));
            OutputStream out = client.getOutputStream();
            out.write(payload);
            client.flush(20_000);
            out.close(); // FIN -> the reader sees EOF after the last byte

            server.join(30_000);
            if (error.get() != null) {
                throw new AssertionError("receiver failed", error.get());
            }
            assertArrayEquals(payload, received.get());
        }
    }

    @Test
    @Timeout(30)
    void transfersOverACleanLink() throws Exception {
        transfer(new DatagramSocket(0), new DatagramSocket(0), 300_000);
    }

    @Test
    @Timeout(30)
    void delayedAckSendsFewerAcksThanDataPackets() throws Exception {
        // The receiver coalesces acks, so it must send materially fewer ST_STATE packets than the ST_DATA
        // packets it receives (without delayed ACK the ratio would be ~1:1).
        CountingDatagramSocket sender = new CountingDatagramSocket(0);
        CountingDatagramSocket receiver = new CountingDatagramSocket(0);
        transfer(sender, receiver, 200_000);

        int dataSent = sender.count(UtpPacket.ST_DATA);
        int acksSent = receiver.count(UtpPacket.ST_STATE);
        assertTrue(dataSent > 50, "expected a meaningful number of data packets, got " + dataSent);
        assertTrue(acksSent < dataSent * 0.75,
                "delayed ACK should coalesce: " + acksSent + " acks for " + dataSent + " data packets");
    }

    /** A DatagramSocket that tallies outgoing uTP packets by type (peeking the type nibble of the first byte). */
    private static final class CountingDatagramSocket extends DatagramSocket {
        private final java.util.concurrent.atomic.AtomicIntegerArray counts = new java.util.concurrent.atomic.AtomicIntegerArray(5);

        CountingDatagramSocket(int port) throws IOException {
            super(port);
        }

        int count(int type) {
            return counts.get(type);
        }

        @Override
        public void send(DatagramPacket p) throws IOException {
            if (p.getLength() > 0) {
                int type = (p.getData()[p.getOffset()] >> 4) & 0x0F;
                if (type < 5) {
                    counts.incrementAndGet(type);
                }
            }
            super.send(p);
        }
    }

    @Test
    @Timeout(60)
    void transfersOverALossyReorderingLink() throws Exception {
        // 8% one-way loss plus reordering on both directions: only retransmission + reassembly get the bytes through.
        transfer(new LossyDatagramSocket(0, 0.08, 0.10, 4242),
                new LossyDatagramSocket(0, 0.08, 0.10, 2424), 150_000);
    }

    /**
     * A DatagramSocket that drops a fraction of outgoing packets and delays (reorders) another fraction, to model
     * a hostile link. Loss and reordering are applied on send; receiving is unchanged.
     */
    private static final class LossyDatagramSocket extends DatagramSocket {
        private final double dropRate;
        private final double reorderRate;
        private final Random random;
        private final ArrayBlockingQueue<DatagramPacket> delayed = new ArrayBlockingQueue<>(1024);
        private volatile boolean closed;

        LossyDatagramSocket(int port, double dropRate, double reorderRate, long seed) throws IOException {
            super(port);
            this.dropRate = dropRate;
            this.reorderRate = reorderRate;
            this.random = new Random(seed);
            Thread.ofVirtual().start(this::flushDelayed);
        }

        @Override
        public void send(DatagramPacket p) throws IOException {
            double r;
            synchronized (random) {
                r = random.nextDouble();
            }
            if (r < dropRate) {
                return; // dropped
            }
            if (r < dropRate + reorderRate) {
                // hold it briefly so a later packet overtakes it
                DatagramPacket copy = new DatagramPacket(p.getData().clone(), p.getLength(), p.getSocketAddress());
                if (!delayed.offer(copy)) {
                    super.send(p);
                }
                return;
            }
            super.send(p);
        }

        private void flushDelayed() {
            while (!closed) {
                try {
                    DatagramPacket p = delayed.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (p != null) {
                        Thread.sleep(15); // reordering delay
                        if (!closed) {
                            super.send(p);
                        }
                    }
                } catch (IOException | InterruptedException e) {
                    return;
                }
            }
        }

        @Override
        public void close() {
            closed = true;
            super.close();
        }
    }
}
