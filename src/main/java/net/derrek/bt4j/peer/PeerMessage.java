package net.derrek.bt4j.peer;

import net.derrek.bt4j.piece.Bitfield;

/**
 * peer wire protocol 訊息（BEP 3；Extended 為 BEP 10）。
 * 線上格式：&lt;length u32&gt;&lt;message id u8&gt;&lt;payload&gt;；length=0 為 keep-alive。
 */
public sealed interface PeerMessage {

    record KeepAlive() implements PeerMessage {
    }

    /** id=0：本端拒絕回應對方的 request。 */
    record Choke() implements PeerMessage {
    }

    /** id=1：允許對方 request。 */
    record Unchoke() implements PeerMessage {
    }

    /** id=2：對對方持有的 piece 有興趣。 */
    record Interested() implements PeerMessage {
    }

    /** id=3。 */
    record NotInterested() implements PeerMessage {
    }

    /** id=4：宣告取得一個完整且驗證過的 piece。 */
    record Have(int pieceIndex) implements PeerMessage {
    }

    /** id=5：handshake 後第一則訊息，宣告持有的 piece 集合。 */
    record BitfieldMessage(Bitfield bitfield) implements PeerMessage {
    }

    /** id=6：請求一個 block（length 慣例 16 KiB，不得超過 128 KiB）。 */
    record Request(int pieceIndex, int begin, int length) implements PeerMessage {
    }

    /** id=7：block 資料。 */
    record Piece(int pieceIndex, int begin, byte[] data) implements PeerMessage {
    }

    /** id=8：取消先前的 request（endgame 用）。 */
    record Cancel(int pieceIndex, int begin, int length) implements PeerMessage {
    }

    /** id=9：告知本機 DHT UDP port（BEP 5，reserved bit 0x01 時）。 */
    record Port(int dhtPort) implements PeerMessage {
    }

    /** id=20：擴充訊息（BEP 10）。extendedId=0 為 extension handshake。 */
    record Extended(int extendedId, byte[] payload) implements PeerMessage {
    }

    // --- Fast Extension（BEP 6，reserved 0x04 時才可使用）---

    /** id=0x0D：suggest piece。 */
    record SuggestPiece(int pieceIndex) implements PeerMessage {
    }

    /** id=0x0E：等同持有全部 piece 的 bitfield。 */
    record HaveAll() implements PeerMessage {
    }

    /** id=0x0F：等同全空 bitfield。 */
    record HaveNone() implements PeerMessage {
    }

    /** id=0x10：明確拒絕一個 request（取代默默不回）。 */
    record RejectRequest(int pieceIndex, int begin, int length) implements PeerMessage {
    }

    /** id=0x11：choke 期間仍允許對方下載的 piece。 */
    record AllowedFast(int pieceIndex) implements PeerMessage {
    }
}
