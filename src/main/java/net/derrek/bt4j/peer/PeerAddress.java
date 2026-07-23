package net.derrek.bt4j.peer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/** peer 的網路位址。來源：tracker compact 回應（BEP 23）、DHT、PEX、magnet x.pe。 */
public record PeerAddress(InetSocketAddress socketAddress) {

    /** 從 compact 格式解析（BEP 23）：每 6 bytes = IPv4(4) + port(2, big-endian)。 */
    public static List<PeerAddress> fromCompact(byte[] compactPeers) {
        return parse(compactPeers, 4);
    }

    /** 從 IPv6 compact 格式解析（BEP 7 的 peers6）：每 18 bytes = IPv6(16) + port(2)。 */
    public static List<PeerAddress> fromCompact6(byte[] compactPeers) {
        return parse(compactPeers, 16);
    }

    private static List<PeerAddress> parse(byte[] data, int addressLength) {
        int stride = addressLength + 2;
        if (data.length % stride != 0) {
            throw new IllegalArgumentException("compact peers 長度必須是 " + stride + " 的倍數: " + data.length);
        }
        List<PeerAddress> result = new ArrayList<>(data.length / stride);
        for (int i = 0; i < data.length; i += stride) {
            try {
                InetAddress address = InetAddress.getByAddress(java.util.Arrays.copyOfRange(data, i, i + addressLength));
                int port = ((data[i + addressLength] & 0xFF) << 8) | (data[i + addressLength + 1] & 0xFF);
                if (port == 0) {
                    continue; // port 0 無法連線，略過
                }
                result.add(new PeerAddress(new InetSocketAddress(address, port)));
            } catch (UnknownHostException e) {
                throw new AssertionError("固定長度位址不可能失敗", e);
            }
        }
        return List.copyOf(result);
    }

    @Override
    public String toString() {
        return socketAddress.getHostString() + ":" + socketAddress.getPort();
    }
}
