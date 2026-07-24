package net.derrek.bt4j.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Shares one {@link DatagramSocket} between DHT (KRPC) and uTP, which BitTorrent expects to reach on the same
 * UDP port. A single receive loop demultiplexes each datagram by its first byte — uTP packets carry
 * {@code (type<<4)|version} with version 1 and type 0–4 (so 0x01/0x11/0x21/0x31/0x41), while KRPC messages are
 * bencoded dictionaries starting with {@code 'd'} (0x64) — and routes it to the matching handler.
 */
public final class UdpMux implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(UdpMux.class.getName());
    private static final int MAX_DATAGRAM = 4096;

    /** Receives a demultiplexed datagram. */
    public interface Handler {
        void onDatagram(byte[] data, int length, InetSocketAddress from);
    }

    private final DatagramSocket socket;
    private volatile Handler utpHandler;
    private volatile Handler dhtHandler;
    private volatile boolean closed;

    public UdpMux(DatagramSocket socket) {
        this.socket = socket;
        Thread.ofVirtual().name("bt4j-udp-mux-" + socket.getLocalPort()).start(this::receiveLoop);
    }

    public int localPort() {
        return socket.getLocalPort();
    }

    public void setUtpHandler(Handler handler) {
        this.utpHandler = handler;
    }

    public void setDhtHandler(Handler handler) {
        this.dhtHandler = handler;
    }

    /** Sends a datagram. Shared by both the DHT and uTP layers. */
    public void send(SocketAddress to, byte[] data) throws IOException {
        socket.send(new DatagramPacket(data, data.length, to));
    }

    /** True if the first byte marks a uTP v1 packet rather than a bencoded KRPC message. */
    static boolean isUtp(byte first) {
        int version = first & 0x0F;
        int type = (first >>> 4) & 0x0F;
        return version == 1 && type <= 4;
    }

    private void receiveLoop() {
        byte[] buffer = new byte[MAX_DATAGRAM];
        while (!closed) {
            DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(datagram);
            } catch (IOException e) {
                if (!closed) {
                    LOG.log(System.Logger.Level.DEBUG, () -> "UDP mux receive stopped: " + e.getMessage());
                }
                return;
            }
            if (datagram.getLength() == 0) {
                continue;
            }
            Handler handler = isUtp(datagram.getData()[datagram.getOffset()]) ? utpHandler : dhtHandler;
            if (handler != null) {
                InetSocketAddress from = (InetSocketAddress) datagram.getSocketAddress();
                try {
                    handler.onDatagram(datagram.getData(), datagram.getLength(), from);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.DEBUG, () -> "UDP mux handler error from " + from + ": " + e.getMessage());
                }
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        socket.close();
    }
}
