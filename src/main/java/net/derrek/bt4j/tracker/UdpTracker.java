package net.derrek.bt4j.tracker;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.OptionalInt;

/**
 * UDP tracker (BEP 15): a two-stage connect → announce.
 * The connection id is cached for 60 seconds (reconnect on expiry); on timeout, retransmit with doubling backoff.
 * Thread-safe (the whole announce is synchronized).
 */
public final class UdpTracker implements Tracker {

    private static final System.Logger LOG = System.getLogger(UdpTracker.class.getName());

    private static final long PROTOCOL_MAGIC = 0x41727101980L;
    private static final int ACTION_CONNECT = 0;
    private static final int ACTION_ANNOUNCE = 1;
    private static final int ACTION_ERROR = 3;
    private static final long CONNECTION_ID_LIFETIME_MILLIS = 50_000; // BEP 15 specifies 60s; conservatively use 50s
    private static final int[] DEFAULT_TIMEOUTS_MILLIS = {5_000, 10_000, 20_000};

    private static final SecureRandom RANDOM = new SecureRandom();

    private final URI uri;
    private final int[] timeoutsMillis;
    private final int key = RANDOM.nextInt(); // BEP 15: identifies the same client when its IP changes

    private DatagramSocket socket;
    private long connectionId;
    private long connectionIdExpiresAt;

    public UdpTracker(URI uri) {
        this(uri, DEFAULT_TIMEOUTS_MILLIS);
    }

    /** For testing: custom retransmit timeout sequence. */
    UdpTracker(URI uri, int[] timeoutsMillis) {
        if (uri.getPort() < 0) {
            throw new IllegalArgumentException("UDP tracker must specify a port: " + uri);
        }
        this.uri = uri;
        this.timeoutsMillis = timeoutsMillis.clone();
    }

    @Override
    public URI uri() {
        return uri;
    }

    @Override
    public synchronized AnnounceResponse announce(AnnounceRequest request) throws TrackerException {
        try {
            InetSocketAddress target = new InetSocketAddress(uri.getHost(), uri.getPort());
            if (target.isUnresolved()) {
                throw new TrackerException("could not resolve tracker host: " + uri);
            }
            ensureSocket();
            ensureConnection(target);
            AnnounceResponse result = doAnnounce(target, request);
            LOG.log(System.Logger.Level.DEBUG, () -> "UDP tracker " + uri + " returned "
                    + result.peers().size() + " peers (interval=" + result.interval().toSeconds() + "s)");
            return result;
        } catch (IOException e) {
            throw new TrackerException("UDP tracker communication failed: " + uri, e);
        }
    }

    private void ensureSocket() throws IOException {
        if (socket == null || socket.isClosed()) {
            socket = new DatagramSocket();
        }
    }

    private void ensureConnection(InetSocketAddress target) throws IOException, TrackerException {
        if (System.currentTimeMillis() < connectionIdExpiresAt) {
            return;
        }
        int transactionId = RANDOM.nextInt();
        ByteBuffer packet = ByteBuffer.allocate(16);
        packet.putLong(PROTOCOL_MAGIC);
        packet.putInt(ACTION_CONNECT);
        packet.putInt(transactionId);

        ByteBuffer response = exchange(target, packet.array(), transactionId, ACTION_CONNECT, 16);
        this.connectionId = response.getLong(8);
        this.connectionIdExpiresAt = System.currentTimeMillis() + CONNECTION_ID_LIFETIME_MILLIS;
    }

    private AnnounceResponse doAnnounce(InetSocketAddress target, AnnounceRequest request)
            throws IOException, TrackerException {
        int transactionId = RANDOM.nextInt();
        ByteBuffer packet = ByteBuffer.allocate(98);
        packet.putLong(connectionId);
        packet.putInt(ACTION_ANNOUNCE);
        packet.putInt(transactionId);
        packet.put(request.infoHash().bytes());
        packet.put(request.peerId().bytes());
        packet.putLong(request.downloaded());
        packet.putLong(request.left());
        packet.putLong(request.uploaded());
        packet.putInt(eventCode(request.event()));
        packet.putInt(0);                    // IP: 0 = let the tracker use the source address
        packet.putInt(key);
        packet.putInt(request.numWant() <= 0 ? -1 : request.numWant());
        packet.putShort((short) request.port());

        ByteBuffer response = exchange(target, packet.array(), transactionId, ACTION_ANNOUNCE, 20);
        Duration interval = Duration.ofSeconds(Integer.toUnsignedLong(response.getInt(8)));
        int leechers = response.getInt(12);
        int seeders = response.getInt(16);
        byte[] compact = new byte[response.limit() - 20];
        response.get(20, compact);
        return new AnnounceResponse(
                interval.isZero() ? Duration.ofMinutes(30) : interval,
                OptionalInt.of(seeders), OptionalInt.of(leechers),
                net.derrek.bt4j.peer.PeerAddress.fromCompact(trimToStride(compact)));
    }

    /** Discards a trailing remainder of fewer than 6 bytes (from a non-compliant tracker). */
    private static byte[] trimToStride(byte[] compact) {
        return compact.length % 6 == 0 ? compact : Arrays.copyOf(compact, compact.length - compact.length % 6);
    }

    /** Sends and waits for a response; retransmits per the timeout sequence; action=3 (error) becomes a TrackerException. */
    private ByteBuffer exchange(InetSocketAddress target, byte[] packet, int expectedTransactionId,
                                int expectedAction, int minLength) throws IOException, TrackerException {
        byte[] receiveBuffer = new byte[4096];
        for (int timeout : timeoutsMillis) {
            socket.send(new DatagramPacket(packet, packet.length, target));
            socket.setSoTimeout(timeout);
            long deadline = System.currentTimeMillis() + timeout;
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket received = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                try {
                    socket.receive(received);
                } catch (SocketTimeoutException e) {
                    break; // this round timed out -> retransmit
                }
                if (received.getLength() < 8) {
                    continue; // noise
                }
                ByteBuffer response = ByteBuffer.wrap(receiveBuffer, 0, received.getLength()).slice();
                int action = response.getInt(0);
                int transactionId = response.getInt(4);
                if (transactionId != expectedTransactionId) {
                    continue; // not this transaction (stale response)
                }
                if (action == ACTION_ERROR) {
                    String message = new String(receiveBuffer, 8, received.getLength() - 8, StandardCharsets.UTF_8);
                    throw new TrackerException("tracker rejected: " + message);
                }
                if (action != expectedAction || received.getLength() < minLength) {
                    continue;
                }
                return response;
            }
        }
        throw new TrackerException("UDP tracker did not respond (retried " + timeoutsMillis.length + " times): " + uri);
    }

    private static int eventCode(AnnounceEvent event) {
        return switch (event) {
            case NONE -> 0;
            case COMPLETED -> 1;
            case STARTED -> 2;
            case STOPPED -> 3;
        };
    }
}
