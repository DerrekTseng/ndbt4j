package net.derrek.bt4j.utp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A single uTP connection (BEP 29): a reliable, in-order, congestion-controlled byte stream over UDP, presented
 * through blocking {@link #getInputStream()} / {@link #getOutputStream()} so it can stand in for a TCP socket.
 *
 * <p>Threading: application threads call read/write/close; the owning {@link UtpEndpoint}'s receive thread calls
 * {@link #onPacket}, and its ticker calls {@link #onTick}. All connection state is guarded by a single lock, and
 * blocked readers/writers wait on conditions. Outbound UDP sends happen through the endpoint while holding the
 * lock — packets are small, so the send does not meaningfully block.
 *
 * <p>Congestion control is LEDBAT: the delay the remote reports (timestamp_difference) drives the window toward a
 * 100&nbsp;ms target queuing delay, and packet loss halves it, exactly so uTP yields to interactive traffic.
 */
public final class UtpSocket {

    private static final System.Logger LOG = System.getLogger(UtpSocket.class.getName());

    static final int MSS = 1400;                 // max payload bytes per packet
    private static final int MIN_WINDOW = MSS;
    private static final long TARGET_MICROS = 100_000; // LEDBAT target queuing delay: 100 ms
    private static final double MAX_CWND_INCREASE_BYTES_PER_RTT = 3000;
    private static final int APP_SEND_BUFFER_LIMIT = 1 << 20; // 1 MiB of unsent app data blocks the writer
    private static final int RECV_BUFFER_LIMIT = 1 << 20;
    private static final long MIN_RTO_MILLIS = 500;
    private static final long INITIAL_RTO_MILLIS = 1000;
    private static final int MAX_RETRANSMITS = 8; // give up on the connection after this many timeouts on one packet

    enum State { SYN_SENT, SYN_RECV, CONNECTED, FIN_SENT, CLOSED, RESET }

    private final UtpEndpoint endpoint;
    private final InetSocketAddress remote;
    private final int connIdSend;
    private final int connIdRecv;
    private final boolean initiator;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition readable = lock.newCondition();
    private final Condition writable = lock.newCondition();
    private final Condition connected = lock.newCondition();

    private State state;

    // send side
    private int seqNr;                 // next sequence number to assign
    private int lastAckReceived;       // highest ack_nr seen (their cumulative ack)
    private int duplicateAcks;
    private final Map<Integer, Outbound> outstanding = new LinkedHashMap<>(); // seq -> in-flight packet, send order
    private final java.io.ByteArrayOutputStream appSendBuffer = new java.io.ByteArrayOutputStream();
    private int appSendOffset;         // bytes of appSendBuffer already packetized and sent
    private long maxWindow = 4L * MSS; // congestion window (bytes)
    private long theirWindow = 4L * MSS; // advertised receive window (flow control)
    private long curWindow;            // bytes in flight

    // receive side
    private int ackNr;                 // highest in-order sequence number received
    private boolean ackNrInitialised;
    private final TreeMap<Integer, byte[]> reorderBuffer = new TreeMap<>(); // out-of-order data by seq
    private final java.util.ArrayDeque<byte[]> recvChunks = new java.util.ArrayDeque<>(); // in-order, ready to read
    private int recvHeadOffset;        // read cursor within the first chunk
    private int recvAvailable;         // total bytes ready to read
    private int eofSeq = -1;           // sequence number of the FIN, -1 until received
    private boolean eofReached;        // in-order stream fully delivered up to (and including) the FIN
    private int acksPending;           // in-order data received but not yet acked (delayed-ACK coalescing)

    // timing / congestion
    // base_delay is the minimum one-way delay seen over the recent past, tracked as per-minute bucket minima so a
    // transient low sample cannot peg it forever and a rising delay floor is followed within a few minutes.
    private static final int BASE_DELAY_BUCKETS = 4; // ~4 minutes of history
    private static final long BASE_DELAY_BUCKET_MICROS = 60_000_000L;
    private final long[] baseDelayBuckets = new long[BASE_DELAY_BUCKETS];
    private int baseDelayBucket;
    private long baseDelayBucketStart;
    private long rttMillis;
    private long rttVarMillis;
    private long rtoMillis = INITIAL_RTO_MILLIS;
    private long replyMicro;           // our latest one-way delay measurement, echoed back in what we send
    private long lastActivityMicros;

    UtpSocket(UtpEndpoint endpoint, InetSocketAddress remote, int connIdSend, int connIdRecv, boolean initiator) {
        this.endpoint = endpoint;
        this.remote = remote;
        this.connIdSend = connIdSend;
        this.connIdRecv = connIdRecv;
        this.initiator = initiator;
        java.util.Arrays.fill(baseDelayBuckets, Long.MAX_VALUE);
    }

    /** The connection id this socket receives on (the endpoint demultiplexes incoming packets by it). */
    int recvConnId() {
        return connIdRecv;
    }

    public InetSocketAddress remote() {
        return remote;
    }

    // ---- handshake ----

    /** Initiator: send ST_SYN and block until connected or the timeout elapses. */
    void connect(long timeoutMillis) throws IOException {
        lock.lock();
        try {
            state = State.SYN_SENT;
            seqNr = 1;
            ackNr = 0;
            // The SYN carries connection_id = our recv id; all later packets use connIdSend (= recv id + 1).
            sendPacket(UtpPacket.ST_SYN, connIdRecv, seqNr, NO_DATA);
            seqNr++;
            long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
            while (state == State.SYN_SENT) {
                long wait = deadline - System.nanoTime();
                if (wait <= 0) {
                    state = State.CLOSED;
                    throw new IOException("uTP connect timed out to " + remote);
                }
                connected.awaitNanos(wait);
            }
            if (state != State.CONNECTED) {
                throw new IOException("uTP connect failed to " + remote + " (state " + state + ")");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted during uTP connect", e);
        } finally {
            lock.unlock();
        }
    }

    /** Accepting side: initialise from the received SYN and reply with an ST_STATE ack. */
    void acceptFrom(UtpPacket syn, long nowMicros) {
        lock.lock();
        try {
            state = State.CONNECTED;
            seqNr = randomSeq();
            ackNr = syn.seqNr();
            ackNrInitialised = true;
            lastActivityMicros = nowMicros;
            updateReplyMicro(syn, nowMicros);
            sendState(); // the SYN-ACK state packet carries seq_nr and, uniquely, consumes it (BEP 29 handshake)
            seqNr = (seqNr + 1) & 0xFFFF; // so our first data packet is one past the value the initiator recorded
        } finally {
            lock.unlock();
        }
    }

    // ---- application I/O ----

    public InputStream getInputStream() {
        return new InputStream() {
            private final byte[] one = new byte[1];

            @Override
            public int read() throws IOException {
                int n = read(one, 0, 1);
                return n < 0 ? -1 : one[0] & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return readInternal(b, off, len);
            }

            @Override
            public int available() {
                lock.lock();
                try {
                    return recvAvailable;
                } finally {
                    lock.unlock();
                }
            }

            @Override
            public void close() {
                UtpSocket.this.close();
            }
        };
    }

    public OutputStream getOutputStream() {
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                writeInternal(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                writeInternal(b, off, len);
            }

            @Override
            public void close() {
                UtpSocket.this.close();
            }
        };
    }

    private int readInternal(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        lock.lock();
        try {
            while (true) {
                if (recvAvailable > 0) {
                    int copied = 0;
                    while (copied < len && recvAvailable > 0) {
                        byte[] head = recvChunks.peekFirst();
                        int n = Math.min(len - copied, head.length - recvHeadOffset);
                        System.arraycopy(head, recvHeadOffset, b, off + copied, n);
                        copied += n;
                        recvHeadOffset += n;
                        recvAvailable -= n;
                        if (recvHeadOffset == head.length) {
                            recvChunks.pollFirst();
                            recvHeadOffset = 0;
                        }
                    }
                    return copied;
                }
                if (eofReached) {
                    return -1; // clean end of stream
                }
                if (state == State.CLOSED || state == State.RESET) {
                    throw new IOException("uTP connection closed");
                }
                readable.await();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted during uTP read", e);
        } finally {
            lock.unlock();
        }
    }

    private void writeInternal(byte[] b, int off, int len) throws IOException {
        lock.lock();
        try {
            int written = 0;
            while (written < len) {
                if (state == State.CLOSED || state == State.RESET || state == State.FIN_SENT) {
                    throw new IOException("uTP connection closed");
                }
                int pending = appSendBuffer.size() - appSendOffset;
                if (pending >= APP_SEND_BUFFER_LIMIT) {
                    writable.await(); // back-pressure: wait for the window to drain
                    continue;
                }
                int chunk = Math.min(len - written, APP_SEND_BUFFER_LIMIT - pending);
                appSendBuffer.write(b, off + written, chunk);
                written += chunk;
            }
            pumpSend(nowMicros());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted during uTP write", e);
        } finally {
            lock.unlock();
        }
    }

    /** Blocks until all buffered application data has been acked (used before a graceful close). */
    void flush(long timeoutMillis) {
        lock.lock();
        try {
            long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
            while ((appSendBuffer.size() > appSendOffset || !outstanding.isEmpty())
                    && state != State.CLOSED && state != State.RESET) {
                long wait = deadline - System.nanoTime();
                if (wait <= 0) {
                    return;
                }
                writable.awaitNanos(wait);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
        }
    }

    public void close() {
        lock.lock();
        try {
            if (state == State.CONNECTED || state == State.SYN_RECV) {
                // Flush what we can, then send a FIN (tracked like data so it is retransmitted until acked).
                long now = nowMicros();
                pumpSend(now);
                sendPacket(UtpPacket.ST_FIN, connIdSend, seqNr, NO_DATA);
                outstanding.put(seqNr, new Outbound(seqNr, UtpPacket.ST_FIN, NO_DATA, now));
                seqNr = (seqNr + 1) & 0xFFFF;
                state = State.FIN_SENT;
            } else if (state != State.RESET) {
                state = State.CLOSED;
            }
            signalAll();
        } finally {
            lock.unlock();
        }
    }

    boolean isClosed() {
        lock.lock();
        try {
            return state == State.CLOSED || state == State.RESET;
        } finally {
            lock.unlock();
        }
    }

    // ---- packet handling (called by the endpoint receive thread) ----

    void onPacket(UtpPacket pkt, long nowMicros) {
        lock.lock();
        try {
            lastActivityMicros = nowMicros;
            if (pkt.type() == UtpPacket.ST_RESET) {
                state = State.RESET;
                signalAll();
                return;
            }
            if (state == State.SYN_SENT) {
                // The awaited ST_STATE completes the handshake; adopt their sequence numbering.
                ackNr = pkt.seqNr();
                ackNrInitialised = true;
                state = State.CONNECTED;
                connected.signalAll();
            }
            updateReplyMicro(pkt, nowMicros);
            theirWindow = Math.max(MIN_WINDOW, pkt.wndSize());
            processAcks(pkt, nowMicros);

            switch (pkt.type()) {
                case UtpPacket.ST_DATA -> onData(pkt);
                case UtpPacket.ST_FIN -> {
                    if (eofSeq < 0) {
                        eofSeq = pkt.seqNr();
                    }
                    onData(pkt); // a FIN participates in the sequence space; deliver anything it carries in order
                }
                case UtpPacket.ST_SYN -> sendState(); // a retransmitted SYN: our earlier ack was lost, re-ack
                case UtpPacket.ST_STATE -> {
                    // pure ack, already processed
                }
                default -> {
                }
            }
            pumpSend(nowMicros);
        } finally {
            lock.unlock();
        }
    }

    private void onData(UtpPacket pkt) {
        int seq = pkt.seqNr();
        if (!ackNrInitialised) {
            return;
        }
        int expected = (ackNr + 1) & 0xFFFF;
        if (UtpPacket.seqLess(seq, expected) || seq == ackNr) {
            sendStateNow(); // already have it (a retransmit): re-ack immediately so the sender advances
            return;
        }
        if (seq == expected) {
            deliverInOrder(pkt.payload(), seq);
            // drain any contiguous packets already sitting in the reorder buffer
            int next = (ackNr + 1) & 0xFFFF;
            while (reorderBuffer.containsKey(next)) {
                byte[] data = reorderBuffer.remove(next);
                deliverInOrder(data, next);
                next = (ackNr + 1) & 0xFFFF;
            }
        } else {
            reorderBuffer.put(seq, pkt.payload()); // out of order: hold it
        }
        // Delayed ACK: coalesce in-order acks, but ack at once when there is a gap (to carry the selective ACK),
        // when a FIN is in play (prompt teardown), or every second packet so the sender's window keeps advancing.
        if (!reorderBuffer.isEmpty() || eofSeq >= 0 || ++acksPending >= 2) {
            sendStateNow();
        }
    }

    /** Sends an ACK now and clears the delayed-ACK counter. */
    private void sendStateNow() {
        acksPending = 0;
        sendState();
    }

    private void deliverInOrder(byte[] data, int seq) {
        if (data.length > 0) {
            recvChunks.addLast(data);
            recvAvailable += data.length;
        }
        ackNr = seq;
        if (eofSeq >= 0 && seq == eofSeq) {
            eofReached = true;
        }
        signalAll();
    }

    private void processAcks(UtpPacket pkt, long nowMicros) {
        int ack = pkt.ackNr();
        boolean advanced = false;
        long ackedBytes = 0;
        // Cumulative ack: remove every outstanding packet up to and including ack_nr.
        var it = outstanding.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Outbound> entry = it.next();
            int seq = entry.getKey();
            if (seq == ack || UtpPacket.seqLess(seq, (ack + 1) & 0xFFFF)) {
                Outbound out = entry.getValue();
                if (out.transmissions == 1) {
                    updateRtt(nowMicros - out.sentAtMicros, nowMicros);
                }
                ackedBytes += out.payloadLen;
                curWindow -= out.payloadLen;
                it.remove();
                advanced = true;
            }
        }
        // Selective ack: mark and remove individually acked packets beyond ack_nr.
        int selectivelyAcked = 0;
        if (pkt.selectiveAck() != null) {
            selectivelyAcked = applySelectiveAck(pkt.selectiveAck(), ack, nowMicros);
        }
        if (advanced) {
            if (ack == lastAckReceived) {
                // same cumulative ack as before -> counts toward duplicate-ack loss detection
            }
            lastAckReceived = ack;
            duplicateAcks = 0;
            onWindowProgress(ackedBytes, pkt.timestampDiffMicros());
        } else if (ack == lastAckReceived && pkt.type() == UtpPacket.ST_STATE && pkt.payload().length == 0) {
            duplicateAcks++;
        }
        // Fast retransmit: 3+ acks past the oldest unacked packet imply it was lost.
        if ((duplicateAcks >= 3 || selectivelyAcked >= 3) && !outstanding.isEmpty()) {
            retransmitOldest(nowMicros, true);
            duplicateAcks = 0;
        }
        if (curWindow < 0) {
            curWindow = 0;
        }
        signalAll();
    }

    private int applySelectiveAck(byte[] mask, int ackNr, long nowMicros) {
        // First bit corresponds to ack_nr + 2 (ack_nr + 1 is assumed lost); bytes are in reverse bit order.
        int count = 0;
        for (int i = 0; i < mask.length * 8; i++) {
            boolean set = (mask[i / 8] & (1 << (i % 8))) != 0;
            if (!set) {
                continue;
            }
            int seq = (ackNr + 2 + i) & 0xFFFF;
            Outbound out = outstanding.remove(seq);
            if (out != null) {
                if (out.transmissions == 1) {
                    updateRtt(nowMicros - out.sentAtMicros, nowMicros);
                }
                curWindow -= out.payloadLen;
                count++;
            }
        }
        return count;
    }

    private void onWindowProgress(long ackedBytes, long theirDelaySample) {
        // LEDBAT: steer max_window toward the target queuing delay using the remote's reported one-way delay.
        if (theirDelaySample <= 0) {
            return; // the remote has no delay measurement yet (timestamp_difference 0): nothing to steer on
        }
        long ourDelay = Math.max(0, theirDelaySample - baseDelay(theirDelaySample));
        double offTarget = (double) (TARGET_MICROS - ourDelay) / TARGET_MICROS;
        double windowFactor = (double) ackedBytes / Math.max(maxWindow, 1);
        double gain = MAX_CWND_INCREASE_BYTES_PER_RTT * offTarget * windowFactor;
        maxWindow = Math.max(MIN_WINDOW, (long) (maxWindow + gain));
    }

    /** Records a delay sample into the rotating per-minute buckets and returns the minimum across the window. */
    private long baseDelay(long sample) {
        long now = nowMicros();
        if (baseDelayBucketStart == 0) {
            baseDelayBucketStart = now;
        }
        while (now - baseDelayBucketStart >= BASE_DELAY_BUCKET_MICROS) {
            baseDelayBucket = (baseDelayBucket + 1) % BASE_DELAY_BUCKETS;
            baseDelayBuckets[baseDelayBucket] = Long.MAX_VALUE; // start the new minute fresh
            baseDelayBucketStart += BASE_DELAY_BUCKET_MICROS;
        }
        if (sample < baseDelayBuckets[baseDelayBucket]) {
            baseDelayBuckets[baseDelayBucket] = sample;
        }
        long min = Long.MAX_VALUE;
        for (long bucket : baseDelayBuckets) {
            min = Math.min(min, bucket);
        }
        return min == Long.MAX_VALUE ? sample : min;
    }

    // ---- sending ----

    private void pumpSend(long nowMicros) {
        byte[] buffered = null; // materialised lazily only if there is something to send
        while (appSendOffset < appSendBuffer.size()) {
            long window = Math.min(maxWindow, theirWindow);
            if (curWindow + MSS > window && curWindow > 0) {
                break; // window full; keep the rest buffered
            }
            if (buffered == null) {
                buffered = appSendBuffer.toByteArray();
            }
            int size = Math.min(MSS, buffered.length - appSendOffset);
            byte[] payload = java.util.Arrays.copyOfRange(buffered, appSendOffset, appSendOffset + size);
            sendPacket(UtpPacket.ST_DATA, connIdSend, seqNr, payload);
            outstanding.put(seqNr, new Outbound(seqNr, UtpPacket.ST_DATA, payload, nowMicros));
            seqNr = (seqNr + 1) & 0xFFFF;
            curWindow += size;
            appSendOffset += size;
        }
        if (appSendOffset == appSendBuffer.size() && appSendOffset > 0) {
            appSendBuffer.reset(); // everything sent: reclaim the buffer
            appSendOffset = 0;
        }
        writable.signalAll();
    }

    private void sendState() {
        sendPacket(UtpPacket.ST_STATE, connIdSend, seqNr, NO_DATA);
    }

    private void sendPacket(int type, int connectionId, int seq, byte[] payload) {
        byte[] sack = (type == UtpPacket.ST_STATE) ? buildSelectiveAck() : null;
        long recvSpace = Math.max(0, RECV_BUFFER_LIMIT - recvAvailable);
        UtpPacket pkt = new UtpPacket(type, connectionId, nowMicros(), replyMicro,
                recvSpace, seq, ackNr, sack, payload);
        try {
            endpoint.send(remote, pkt.encode());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, () -> "uTP send failed to " + remote + ": " + e.getMessage());
        }
    }

    /** Builds a selective-ack bitmask if (and only if) packets beyond ack_nr+1 are buffered out of order. */
    private byte[] buildSelectiveAck() {
        if (reorderBuffer.isEmpty()) {
            return null;
        }
        int base = (ackNr + 2) & 0xFFFF; // first bit represents ack_nr + 2
        int maxBit = 0;
        for (int seq : reorderBuffer.keySet()) {
            int bit = UtpPacket.seqDistance(seq, base);
            if (bit >= 0) {
                maxBit = Math.max(maxBit, bit);
            }
        }
        int bytes = Math.max(4, ((maxBit / 8) + 1 + 3) / 4 * 4); // at least 4, multiple of 4
        byte[] mask = new byte[bytes];
        for (int seq : reorderBuffer.keySet()) {
            int bit = UtpPacket.seqDistance(seq, base);
            if (bit >= 0 && bit < bytes * 8) {
                mask[bit / 8] |= (byte) (1 << (bit % 8));
            }
        }
        return mask;
    }

    // ---- timers (called by the endpoint ticker) ----

    void onTick(long nowMicros) {
        lock.lock();
        try {
            if (state == State.CLOSED || state == State.RESET) {
                return;
            }
            if (acksPending > 0) {
                sendStateNow(); // flush a coalesced ACK that has been waiting since the last tick
            }
            if (outstanding.isEmpty()) {
                if (state == State.FIN_SENT) {
                    state = State.CLOSED; // FIN acked and nothing left to send
                    signalAll();
                }
                return;
            }
            Outbound oldest = outstanding.values().iterator().next();
            long ageMillis = (nowMicros - oldest.sentAtMicros) / 1000;
            if (ageMillis >= rtoMillis) {
                retransmitOldest(nowMicros, false);
            }
        } finally {
            lock.unlock();
        }
    }

    private void retransmitOldest(long nowMicros, boolean fast) {
        Outbound oldest = outstanding.values().iterator().next();
        if (++oldest.transmissions > MAX_RETRANSMITS) {
            state = State.RESET;
            signalAll();
            return;
        }
        if (!fast) {
            // timeout: collapse the window and back the timer off exponentially (spec)
            maxWindow = MIN_WINDOW;
            rtoMillis = Math.min(rtoMillis * 2, 60_000);
        } else {
            maxWindow = Math.max(MIN_WINDOW, maxWindow / 2);
        }
        oldest.sentAtMicros = nowMicros;
        // Re-send the oldest DATA packet. We do not have its bytes stored separately, so resend from record.
        UtpPacket pkt = new UtpPacket(oldest.type, connIdSend, nowMicros(), replyMicro,
                Math.max(0, RECV_BUFFER_LIMIT - recvAvailable),
                oldest.seq, ackNr, null, oldest.payload);
        try {
            endpoint.send(remote, pkt.encode());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, () -> "uTP retransmit failed: " + e.getMessage());
        }
    }

    private void updateRtt(long packetRttMicros, long nowMicros) {
        long packetRttMillis = Math.max(1, packetRttMicros / 1000);
        if (rttMillis == 0) {
            rttMillis = packetRttMillis;
            rttVarMillis = packetRttMillis / 2;
        } else {
            long delta = rttMillis - packetRttMillis;
            rttVarMillis += (Math.abs(delta) - rttVarMillis) / 4;
            rttMillis += (packetRttMillis - rttMillis) / 8;
        }
        rtoMillis = Math.max(MIN_RTO_MILLIS, rttMillis + rttVarMillis * 4);
    }

    private void updateReplyMicro(UtpPacket pkt, long nowMicros) {
        // The one-way delay of the link remote -> us, echoed back so the remote can run its own LEDBAT.
        replyMicro = (nowMicros - pkt.timestampMicros()) & 0xFFFFFFFFL;
    }

    private void signalAll() {
        readable.signalAll();
        writable.signalAll();
        connected.signalAll();
    }

    private static long nowMicros() {
        return (System.nanoTime() / 1000) & 0xFFFFFFFFL;
    }

    private static int randomSeq() {
        return 1 + (int) (Math.floor(Math.abs(System.nanoTime()) % 60000));
    }

    private static final byte[] NO_DATA = new byte[0];

    /** A packet still in flight (kept whole for retransmission and RTT sampling). */
    private static final class Outbound {
        final int seq;
        final int type;
        final byte[] payload;
        final int payloadLen;
        long sentAtMicros;
        int transmissions;

        Outbound(int seq, int type, byte[] payload, long sentAtMicros) {
            this.seq = seq;
            this.type = type;
            this.payload = payload;
            this.payloadLen = payload.length;
            this.sentAtMicros = sentAtMicros;
            this.transmissions = 1;
        }
    }
}
