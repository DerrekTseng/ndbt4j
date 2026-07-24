package net.derrek.bt4j.utp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Owns one {@link DatagramSocket} and multiplexes many {@link UtpSocket} connections over it (BEP 29). One virtual
 * thread reads datagrams and dispatches them by (remote address, connection id); another ticks every 100&nbsp;ms
 * to drive retransmission timers. Incoming ST_SYN packets create accepted connections queued for {@link #accept}.
 *
 * <p>This is the transport a peer connection runs over instead of TCP: {@link #connect} dials out, {@link #accept}
 * takes inbound connections, and each returned {@link UtpSocket} exposes blocking streams.
 */
public final class UtpEndpoint implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(UtpEndpoint.class.getName());

    private static final int MAX_DATAGRAM = 2048;
    private static final long TICK_MILLIS = 100;
    private static final long CONNECT_TIMEOUT_MILLIS = 10_000;

    /** Sends a datagram on behalf of the endpoint (a raw socket, or a shared {@code UdpMux}). */
    public interface Sink {
        void send(SocketAddress to, byte[] data) throws IOException;
    }

    private final Sink sink;
    private final int localPort;
    private final DatagramSocket ownSocket; // non-null only when this endpoint owns its socket (standalone mode)
    private final Map<String, UtpSocket> connections = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<UtpSocket> acceptQueue = new LinkedBlockingQueue<>();
    private volatile boolean closed;

    /**
     * Shared mode: the endpoint sends through {@code sink} and is fed inbound packets via {@link #onDatagram}
     * (e.g. by a {@code UdpMux} that also serves DHT). Only the retransmission ticker runs here.
     */
    public UtpEndpoint(Sink sink, int localPort) {
        this.sink = sink;
        this.localPort = localPort;
        this.ownSocket = null;
        Thread.ofVirtual().name("bt4j-utp-tick-" + localPort).start(this::tickLoop);
    }

    /** Standalone mode: the endpoint owns {@code socket}, running both its own receive loop and the ticker. */
    public UtpEndpoint(DatagramSocket socket) {
        this.ownSocket = socket;
        this.localPort = socket.getLocalPort();
        this.sink = (to, data) -> socket.send(new DatagramPacket(data, data.length, to));
        Thread.ofVirtual().name("bt4j-utp-recv-" + localPort).start(this::receiveLoop);
        Thread.ofVirtual().name("bt4j-utp-tick-" + localPort).start(this::tickLoop);
    }

    /** Binds a fresh endpoint on the given UDP port (0 = system-assigned). */
    public static UtpEndpoint bind(int port) throws SocketException {
        DatagramSocket s = new DatagramSocket(port);
        return new UtpEndpoint(s);
    }

    public int localPort() {
        return localPort;
    }

    private static String key(SocketAddress address, int connId) {
        return address + "#" + connId;
    }

    /** Dials a uTP connection to {@code remote}, blocking until connected or the connect timeout elapses. */
    public UtpSocket connect(InetSocketAddress remote) throws IOException {
        int recvId = ThreadLocalRandom.current().nextInt(1, 0xFFFF);
        int sendId = (recvId + 1) & 0xFFFF;
        UtpSocket sock = new UtpSocket(this, remote, sendId, recvId, true);
        connections.put(key(remote, recvId), sock);
        try {
            sock.connect(CONNECT_TIMEOUT_MILLIS);
        } catch (IOException e) {
            connections.remove(key(remote, recvId));
            throw e;
        }
        return sock;
    }

    /** Blocks for the next inbound connection, or null if the endpoint is closed while waiting. */
    public UtpSocket accept() throws InterruptedException {
        while (!closed) {
            UtpSocket sock = acceptQueue.poll(1, TimeUnit.SECONDS);
            if (sock != null) {
                return sock;
            }
        }
        return null;
    }

    void send(SocketAddress remote, byte[] data) throws IOException {
        if (closed) {
            throw new IOException("uTP endpoint closed");
        }
        sink.send(remote, data);
    }

    /** Feeds an inbound datagram (called by a {@code UdpMux} in shared mode). */
    public void onDatagram(byte[] data, int length, InetSocketAddress from) {
        UtpPacket pkt = UtpPacket.decode(data, length);
        if (pkt != null) {
            dispatch(pkt, from, nowMicros());
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[MAX_DATAGRAM];
        while (!closed) {
            DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
            try {
                ownSocket.receive(datagram);
            } catch (IOException e) {
                if (!closed) {
                    LOG.log(System.Logger.Level.DEBUG, () -> "uTP receive stopped: " + e.getMessage());
                }
                return;
            }
            onDatagram(datagram.getData(), datagram.getLength(), (InetSocketAddress) datagram.getSocketAddress());
        }
    }

    private void dispatch(UtpPacket pkt, InetSocketAddress from, long nowMicros) {
        if (pkt.type() == UtpPacket.ST_SYN) {
            // The accepting socket receives on the SYN's connection_id + 1 (see BEP 29 connection setup).
            int recvId = (pkt.connectionId() + 1) & 0xFFFF;
            String key = key(from, recvId);
            UtpSocket existing = connections.get(key);
            if (existing != null) {
                existing.onPacket(pkt, nowMicros); // duplicate SYN: re-ack
                return;
            }
            UtpSocket sock = new UtpSocket(this, from, pkt.connectionId(), recvId, false);
            connections.put(key, sock);
            sock.acceptFrom(pkt, nowMicros);
            acceptQueue.offer(sock);
            return;
        }
        UtpSocket sock = connections.get(key(from, pkt.connectionId()));
        if (sock != null) {
            sock.onPacket(pkt, nowMicros);
        }
        // Unknown connection: silently dropped (a spec-optional ST_RESET could be sent here).
    }

    private void tickLoop() {
        while (!closed) {
            try {
                Thread.sleep(TICK_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
            long now = nowMicros();
            for (Map.Entry<String, UtpSocket> entry : connections.entrySet()) {
                UtpSocket sock = entry.getValue();
                sock.onTick(now);
                if (sock.isClosed()) {
                    connections.remove(entry.getKey(), sock); // reap; late packets for it are simply dropped
                }
            }
        }
    }

    private static long nowMicros() {
        return (System.nanoTime() / 1000) & 0xFFFFFFFFL;
    }

    @Override
    public void close() {
        closed = true;
        if (ownSocket != null) {
            ownSocket.close(); // shared mode: the UdpMux owns and closes the socket
        }
    }
}
