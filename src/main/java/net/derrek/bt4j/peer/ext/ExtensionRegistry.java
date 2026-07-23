package net.derrek.bt4j.peer.ext;

import net.derrek.bt4j.peer.PeerConnection;
import net.derrek.bt4j.peer.PeerMessage;

/**
 * BEP 10 擴充協定框架：
 * 產生／解析 extension handshake（extended id=0 的 bencoded 字典）、
 * 維護本端與對端的 name ↔ message id 對映、將 Extended 訊息分派給註冊的 {@link Extension}。
 * 每條 PeerConnection 一個實例。
 */
public final class ExtensionRegistry {

    public ExtensionRegistry(java.util.List<Extension> extensions) {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** 產生本端 extension handshake 訊息（連線建立且雙方 reserved bit 支援時送出）。 */
    public PeerMessage.Extended buildHandshake(int metadataSize) {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** 處理收到的 Extended 訊息：id=0 走 handshake，否則分派給對應擴充。 */
    public void dispatch(PeerConnection connection, PeerMessage.Extended message) {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** 以擴充名稱送訊息（自動換成對方宣告的 id）。對方不支援時回傳 false。 */
    public boolean send(PeerConnection connection, String extensionName, byte[] payload) {
        throw new UnsupportedOperationException("尚未實作");
    }
}
