package net.derrek.bt4j.peer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/** A peer's network address. Sources: tracker compact response (BEP 23), DHT, PEX, magnet x.pe. */
public record PeerAddress(InetSocketAddress socketAddress) {

    /** Parse compact format (BEP 23): each 6 bytes = IPv4(4) + port(2, big-endian). */
    public static List<PeerAddress> fromCompact(byte[] compactPeers) {
        return parse(compactPeers, 4);
    }

    /** Parse IPv6 compact format (BEP 7 peers6): each 18 bytes = IPv6(16) + port(2). */
    public static List<PeerAddress> fromCompact6(byte[] compactPeers) {
        return parse(compactPeers, 16);
    }

    private static List<PeerAddress> parse(byte[] data, int addressLength) {
        int stride = addressLength + 2;
        if (data.length % stride != 0) {
            throw new IllegalArgumentException("compact peers length must be a multiple of " + stride + ": " + data.length);
        }
        List<PeerAddress> result = new ArrayList<>(data.length / stride);
        for (int i = 0; i < data.length; i += stride) {
            try {
                InetAddress address = InetAddress.getByAddress(java.util.Arrays.copyOfRange(data, i, i + addressLength));
                int port = ((data[i + addressLength] & 0xFF) << 8) | (data[i + addressLength + 1] & 0xFF);
                if (port == 0) {
                    continue; // port 0 is not connectable, skip
                }
                result.add(new PeerAddress(new InetSocketAddress(address, port)));
            } catch (UnknownHostException e) {
                throw new AssertionError("fixed-length address cannot fail", e);
            }
        }
        return List.copyOf(result);
    }

    @Override
    public String toString() {
        return socketAddress.getHostString() + ":" + socketAddress.getPort();
    }
}
