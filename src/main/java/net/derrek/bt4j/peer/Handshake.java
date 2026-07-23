package net.derrek.bt4j.peer;

import net.derrek.bt4j.metainfo.InfoHash;

/**
 * peer wire handshake（BEP 3）：
 * &lt;19&gt;&lt;"BitTorrent protocol"&gt;&lt;reserved 8 bytes&gt;&lt;info-hash 20&gt;&lt;peer-id 20&gt;。
 * reserved bits：bit 20（第 6 byte 的 0x10）= 支援擴充協定（BEP 10）、
 * 最後一 byte 的 0x04 = Fast Extension（BEP 6）、0x01 = DHT（BEP 5 port 訊息）。
 */
public record Handshake(byte[] reserved, InfoHash infoHash, PeerId peerId) {

    /** 本套件送出的 handshake（依已啟用功能設定 reserved bits）。 */
    public static Handshake outgoing(InfoHash infoHash, PeerId peerId, boolean dht, boolean extensions, boolean fast) {
        throw new UnsupportedOperationException("尚未實作");
    }

    public boolean supportsExtensionProtocol() {
        throw new UnsupportedOperationException("尚未實作");
    }

    public boolean supportsDht() {
        throw new UnsupportedOperationException("尚未實作");
    }

    public boolean supportsFastExtension() {
        throw new UnsupportedOperationException("尚未實作");
    }

    public byte[] encode() {
        throw new UnsupportedOperationException("尚未實作");
    }

    public static Handshake decode(byte[] data) {
        throw new UnsupportedOperationException("尚未實作");
    }
}
