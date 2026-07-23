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
 * UDP tracker（BEP 15）：connect → announce 二段式。
 * connection id 快取 60 秒（過期重新 connect）；逾時逐次翻倍重送。
 * 執行緒安全（announce 整段同步）。
 */
public final class UdpTracker implements Tracker {

    private static final long PROTOCOL_MAGIC = 0x41727101980L;
    private static final int ACTION_CONNECT = 0;
    private static final int ACTION_ANNOUNCE = 1;
    private static final int ACTION_ERROR = 3;
    private static final long CONNECTION_ID_LIFETIME_MILLIS = 50_000; // BEP 15 定 60s，保守取 50s
    private static final int[] DEFAULT_TIMEOUTS_MILLIS = {5_000, 10_000, 20_000};

    private static final SecureRandom RANDOM = new SecureRandom();

    private final URI uri;
    private final int[] timeoutsMillis;
    private final int key = RANDOM.nextInt(); // BEP 15：同一 client 換 IP 時的識別

    private DatagramSocket socket;
    private long connectionId;
    private long connectionIdExpiresAt;

    public UdpTracker(URI uri) {
        this(uri, DEFAULT_TIMEOUTS_MILLIS);
    }

    /** 測試用：自訂重送逾時序列。 */
    UdpTracker(URI uri, int[] timeoutsMillis) {
        if (uri.getPort() < 0) {
            throw new IllegalArgumentException("UDP tracker 必須指定 port: " + uri);
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
                throw new TrackerException("無法解析 tracker 主機: " + uri);
            }
            ensureSocket();
            ensureConnection(target);
            return doAnnounce(target, request);
        } catch (IOException e) {
            throw new TrackerException("UDP tracker 通訊失敗: " + uri, e);
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
        packet.putInt(0);                    // IP：0 = 由 tracker 取來源位址
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

    /** 尾端不足 6 bytes 的殘餘（不合規 tracker）直接捨棄。 */
    private static byte[] trimToStride(byte[] compact) {
        return compact.length % 6 == 0 ? compact : Arrays.copyOf(compact, compact.length - compact.length % 6);
    }

    /** 送出並等待回應；逾時依序列重送；action=3（error）轉為 TrackerException。 */
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
                    break; // 本輪逾時 → 重送
                }
                if (received.getLength() < 8) {
                    continue; // 雜訊
                }
                ByteBuffer response = ByteBuffer.wrap(receiveBuffer, 0, received.getLength()).slice();
                int action = response.getInt(0);
                int transactionId = response.getInt(4);
                if (transactionId != expectedTransactionId) {
                    continue; // 不是這筆交易（過期回應）
                }
                if (action == ACTION_ERROR) {
                    String message = new String(receiveBuffer, 8, received.getLength() - 8, StandardCharsets.UTF_8);
                    throw new TrackerException("tracker 拒絕: " + message);
                }
                if (action != expectedAction || received.getLength() < minLength) {
                    continue;
                }
                return response;
            }
        }
        throw new TrackerException("UDP tracker 無回應（已重試 " + timeoutsMillis.length + " 次）: " + uri);
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
