package net.derrek.bt4j.peer.ext;

import net.derrek.bt4j.bencode.BValue;
import net.derrek.bt4j.peer.PeerConnection;

/**
 * BEP 10 擴充的 SPI。每個擴充（ut_metadata、ut_pex…）實作此介面，
 * 註冊到 {@link ExtensionRegistry}。
 */
public interface Extension {

    /** 擴充名稱，用於 extension handshake 的 m 字典（如 "ut_metadata"）。 */
    String name();

    /**
     * 對方的 extension handshake 到達。
     * 可從 handshake 讀取對方指定的訊息 id 與附加欄位（如 metadata_size）。
     */
    void onExtensionHandshake(PeerConnection connection, BValue.BDictionary handshake);

    /** 收到屬於本擴充的訊息（依對方 handshake 宣告的 id 分派）。 */
    void onMessage(PeerConnection connection, byte[] payload);
}
