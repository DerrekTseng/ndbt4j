package net.derrek.bt4j.peer;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import net.derrek.bt4j.metainfo.InfoHash;

/**
 * peer wire handshake (BEP 3):
 * &lt;19&gt;&lt;"BitTorrent protocol"&gt;&lt;reserved 8 bytes&gt;&lt;info-hash 20&gt;&lt;peer-id 20&gt;, 68 bytes total.
 * reserved bits: reserved[5] 0x10 = extension protocol (BEP 10),
 * reserved[7] 0x04 = Fast Extension (BEP 6), reserved[7] 0x01 = DHT (BEP 5 port message).
 */
public record Handshake(byte[] reserved, InfoHash infoHash, PeerId peerId) {

    public static final int LENGTH = 68;

    private static final byte[] PROTOCOL = "BitTorrent protocol".getBytes(StandardCharsets.US_ASCII);

    public Handshake {
        if (reserved.length != 8) {
            throw new IllegalArgumentException("reserved must be 8 bytes");
        }
    }

    /** Handshake sent by this library (reserved bits set according to enabled features). */
    public static Handshake outgoing(InfoHash infoHash, PeerId peerId, boolean dht, boolean extensions, boolean fast) {
        byte[] reserved = new byte[8];
        if (extensions) {
            reserved[5] |= 0x10;
        }
        if (fast) {
            reserved[7] |= 0x04;
        }
        if (dht) {
            reserved[7] |= 0x01;
        }
        return new Handshake(reserved, infoHash, peerId);
    }

    public boolean supportsExtensionProtocol() {
        return (reserved[5] & 0x10) != 0;
    }

    public boolean supportsDht() {
        return (reserved[7] & 0x01) != 0;
    }

    public boolean supportsFastExtension() {
        return (reserved[7] & 0x04) != 0;
    }

    public byte[] encode() {
        byte[] out = new byte[LENGTH];
        out[0] = (byte) PROTOCOL.length;
        System.arraycopy(PROTOCOL, 0, out, 1, PROTOCOL.length);
        System.arraycopy(reserved, 0, out, 20, 8);
        System.arraycopy(infoHash.bytes(), 0, out, 28, 20);
        System.arraycopy(peerId.bytes(), 0, out, 48, 20);
        return out;
    }

    public static Handshake decode(byte[] data) {
        if (data.length != LENGTH) {
            throw new IllegalArgumentException("handshake must be " + LENGTH + " bytes, got " + data.length);
        }
        if (data[0] != PROTOCOL.length
                || !Arrays.equals(data, 1, 20, PROTOCOL, 0, PROTOCOL.length)) {
            throw new IllegalArgumentException("not a BitTorrent protocol handshake");
        }
        return new Handshake(
                Arrays.copyOfRange(data, 20, 28),
                new InfoHash(Arrays.copyOfRange(data, 28, 48)),
                new PeerId(Arrays.copyOfRange(data, 48, 68)));
    }
}
