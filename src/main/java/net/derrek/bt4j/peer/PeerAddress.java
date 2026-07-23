package net.derrek.bt4j.peer;

import java.net.InetSocketAddress;

/** peer 的網路位址。來源：tracker compact 回應（BEP 23）、DHT、PEX、magnet x.pe。 */
public record PeerAddress(InetSocketAddress socketAddress) {

    /** 從 compact 格式解析：每 6 bytes = IPv4(4) + port(2, big-endian)。 */
    public static java.util.List<PeerAddress> fromCompact(byte[] compactPeers) {
        throw new UnsupportedOperationException("尚未實作");
    }
}
