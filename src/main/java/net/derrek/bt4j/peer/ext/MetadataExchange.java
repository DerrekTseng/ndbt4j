package net.derrek.bt4j.peer.ext;

import java.util.concurrent.CompletableFuture;
import net.derrek.bt4j.metainfo.InfoHash;

/**
 * ut_metadata（BEP 9）：磁力連結情境下向 peer 索取 info 字典。
 * 以 16 KiB 為單位向多個 peer 平行請求 piece，
 * 全部到齊後驗證 SHA-1 == infoHash 才完成；驗證失敗則丟棄重來（防偽造）。
 * 每個 torrent（session）一個實例，跨連線共享進度。
 */
public final class MetadataExchange implements Extension {

    public MetadataExchange(InfoHash expected) {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** 驗證通過的 info 字典原始位元組。取得前加入的連線都會被用來請求。 */
    public CompletableFuture<byte[]> metadata() {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** 已持有 metadata 時（.torrent 情境）設定之，讓本端可回應他人的 request。 */
    public void supply(byte[] infoDictBytes) {
        throw new UnsupportedOperationException("尚未實作");
    }

    @Override
    public String name() {
        return "ut_metadata";
    }

    @Override
    public void onExtensionHandshake(net.derrek.bt4j.peer.PeerConnection connection,
                                     net.derrek.bt4j.bencode.BValue.BDictionary handshake) {
        throw new UnsupportedOperationException("尚未實作");
    }

    @Override
    public void onMessage(net.derrek.bt4j.peer.PeerConnection connection, byte[] payload) {
        throw new UnsupportedOperationException("尚未實作");
    }
}
