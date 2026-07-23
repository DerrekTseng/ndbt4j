package net.derrek.bt4j.peer;

import java.io.IOException;
import net.derrek.bt4j.metainfo.InfoHash;
import net.derrek.bt4j.piece.Bitfield;

/**
 * 一條 peer TCP 連線的生命週期：handshake、訊息編解碼、四態旗標
 * （am_choking / am_interested / peer_choking / peer_interested）。
 *
 * 執行緒模型：每條連線兩條 virtual thread（讀迴圈 + 寫佇列），
 * 事件透過 {@link Listener} 回呼給 session 層；連出與連入共用本類別。
 */
public final class PeerConnection implements AutoCloseable {

    /** 連線事件回呼。由讀迴圈 thread 呼叫，實作不可長時間阻塞。 */
    public interface Listener {

        void onHandshakeCompleted(PeerConnection connection, Handshake theirs);

        void onMessage(PeerConnection connection, PeerMessage message);

        /** 連線關閉（正常或錯誤）。error 為 null 表示正常關閉。 */
        void onClosed(PeerConnection connection, IOException error);
    }

    /** 主動連出。建構後呼叫 {@link #start()} 才開始 IO。 */
    public static PeerConnection outgoing(PeerAddress address, InfoHash infoHash, PeerId localId, Listener listener) {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** 被動連入（listener socket accept 後）：先讀對方 handshake 以決定所屬 torrent。 */
    public static PeerConnection incoming(java.net.Socket socket, Listener listener) {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** 啟動讀寫 virtual thread、進行 handshake。 */
    public void start() {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** 非同步送出訊息（進寫佇列）。 */
    public void send(PeerMessage message) {
        throw new UnsupportedOperationException("尚未實作");
    }

    public PeerAddress address() {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** 對方宣告的 piece 持有狀態（bitfield + have 累積）。 */
    public Bitfield peerBitfield() {
        throw new UnsupportedOperationException("尚未實作");
    }

    public boolean amChoking() {
        throw new UnsupportedOperationException("尚未實作");
    }

    public boolean amInterested() {
        throw new UnsupportedOperationException("尚未實作");
    }

    public boolean peerChoking() {
        throw new UnsupportedOperationException("尚未實作");
    }

    public boolean peerInterested() {
        throw new UnsupportedOperationException("尚未實作");
    }

    @Override
    public void close() {
        throw new UnsupportedOperationException("尚未實作");
    }
}
