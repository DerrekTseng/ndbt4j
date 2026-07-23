package net.derrek.bt4j.peer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import net.derrek.bt4j.piece.Bitfield;

/**
 * peer wire protocol 訊息（BEP 3；Extended 為 BEP 10；0x0D-0x11 為 BEP 6 Fast Extension）。
 * 線上格式：&lt;length u32&gt;&lt;message id u8&gt;&lt;payload&gt;；length=0 為 keep-alive。
 */
public sealed interface PeerMessage {

    /** 單則訊息長度上限（最大 block 128 KiB + 標頭餘裕），超過視為協定錯誤。 */
    int MAX_MESSAGE_LENGTH = 128 * 1024 + 64;

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

    // ---- 編碼 ----

    static void write(DataOutputStream out, PeerMessage message) throws IOException {
        switch (message) {
            case KeepAlive() -> out.writeInt(0);
            case Choke() -> writeHeader(out, 1, 0);
            case Unchoke() -> writeHeader(out, 1, 1);
            case Interested() -> writeHeader(out, 1, 2);
            case NotInterested() -> writeHeader(out, 1, 3);
            case Have(int piece) -> {
                writeHeader(out, 5, 4);
                out.writeInt(piece);
            }
            case BitfieldMessage(Bitfield bitfield) -> {
                byte[] bytes = bitfield.toBytes();
                writeHeader(out, 1 + bytes.length, 5);
                out.write(bytes);
            }
            case Request(int piece, int begin, int length) -> {
                writeHeader(out, 13, 6);
                out.writeInt(piece);
                out.writeInt(begin);
                out.writeInt(length);
            }
            case Piece(int piece, int begin, byte[] data) -> {
                writeHeader(out, 9 + data.length, 7);
                out.writeInt(piece);
                out.writeInt(begin);
                out.write(data);
            }
            case Cancel(int piece, int begin, int length) -> {
                writeHeader(out, 13, 8);
                out.writeInt(piece);
                out.writeInt(begin);
                out.writeInt(length);
            }
            case Port(int port) -> {
                writeHeader(out, 3, 9);
                out.writeShort(port);
            }
            case SuggestPiece(int piece) -> {
                writeHeader(out, 5, 0x0D);
                out.writeInt(piece);
            }
            case HaveAll() -> writeHeader(out, 1, 0x0E);
            case HaveNone() -> writeHeader(out, 1, 0x0F);
            case RejectRequest(int piece, int begin, int length) -> {
                writeHeader(out, 13, 0x10);
                out.writeInt(piece);
                out.writeInt(begin);
                out.writeInt(length);
            }
            case AllowedFast(int piece) -> {
                writeHeader(out, 5, 0x11);
                out.writeInt(piece);
            }
            case Extended(int extendedId, byte[] payload) -> {
                writeHeader(out, 2 + payload.length, 20);
                out.writeByte(extendedId);
                out.write(payload);
            }
        }
        out.flush();
    }

    private static void writeHeader(DataOutputStream out, int length, int id) throws IOException {
        out.writeInt(length);
        out.writeByte(id);
    }

    // ---- 解碼 ----

    /**
     * 讀取下一則訊息（阻塞）。未知的 message id 整則略過並繼續讀（相容性），
     * 長度異常或串流結束拋 IOException。
     *
     * @param pieceCount 用於驗證 bitfield 長度
     */
    static PeerMessage read(DataInputStream in, int pieceCount) throws IOException {
        while (true) {
            int length = in.readInt();
            if (length == 0) {
                return new KeepAlive();
            }
            if (length < 0 || length > MAX_MESSAGE_LENGTH) {
                throw new IOException("訊息長度異常: " + length);
            }
            int id = in.readUnsignedByte();
            byte[] payload = new byte[length - 1];
            in.readFully(payload);
            PeerMessage message = parse(id, payload, pieceCount);
            if (message != null) {
                return message;
            }
        }
    }

    private static PeerMessage parse(int id, byte[] payload, int pieceCount) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(payload);
        try {
            return switch (id) {
                case 0 -> new Choke();
                case 1 -> new Unchoke();
                case 2 -> new Interested();
                case 3 -> new NotInterested();
                case 4 -> new Have(buf.getInt());
                case 5 -> new BitfieldMessage(Bitfield.fromBytes(payload, pieceCount));
                case 6 -> new Request(buf.getInt(), buf.getInt(), buf.getInt());
                case 7 -> {
                    int piece = buf.getInt();
                    int begin = buf.getInt();
                    byte[] data = new byte[buf.remaining()];
                    buf.get(data);
                    yield new Piece(piece, begin, data);
                }
                case 8 -> new Cancel(buf.getInt(), buf.getInt(), buf.getInt());
                case 9 -> new Port(buf.getShort() & 0xFFFF);
                case 0x0D -> new SuggestPiece(buf.getInt());
                case 0x0E -> new HaveAll();
                case 0x0F -> new HaveNone();
                case 0x10 -> new RejectRequest(buf.getInt(), buf.getInt(), buf.getInt());
                case 0x11 -> new AllowedFast(buf.getInt());
                case 20 -> {
                    int extendedId = buf.get() & 0xFF;
                    byte[] data = new byte[buf.remaining()];
                    buf.get(data);
                    yield new Extended(extendedId, data);
                }
                default -> null; // 未知 id：呼叫端略過
            };
        } catch (java.nio.BufferUnderflowException | IllegalArgumentException e) {
            throw new IOException("訊息 id=" + id + " 的 payload 格式錯誤", e);
        }
    }
}
