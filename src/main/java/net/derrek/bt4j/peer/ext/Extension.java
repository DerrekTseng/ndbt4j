package net.derrek.bt4j.peer.ext;

import net.derrek.bt4j.bencode.BValue;
import net.derrek.bt4j.peer.PeerConnection;

/**
 * BEP 10 擴充的 SPI。每個擴充（ut_metadata、ut_pex…）實作此介面，
 * 註冊到 {@link ExtensionRegistry}（每條連線一個 registry；擴充實例可跨連線共享）。
 * 回呼皆在連線的讀迴圈 thread 上執行；registry 參數是對該連線的回覆通道。
 */
public interface Extension {

    /** 擴充名稱，用於 extension handshake 的 m 字典（如 "ut_metadata"）。 */
    String name();

    /**
     * 對方的 extension handshake 到達（此時 {@link ExtensionRegistry#peerSupports} 已可查詢）。
     * 可從 handshake 讀取附加欄位（如 metadata_size）。
     */
    void onExtensionHandshake(PeerConnection connection, ExtensionRegistry registry, BValue.BDictionary handshake);

    /** 收到屬於本擴充的訊息（依本端宣告的 id 分派）。 */
    void onMessage(PeerConnection connection, ExtensionRegistry registry, byte[] payload);
}
