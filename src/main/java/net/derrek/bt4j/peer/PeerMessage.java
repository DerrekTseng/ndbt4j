package net.derrek.bt4j.peer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import net.derrek.bt4j.piece.Bitfield;

/**
 * Peer wire protocol messages (BEP 3; Extended is BEP 10; 0x0D-0x11 are BEP 6 Fast Extension).
 * Wire format: &lt;length u32&gt;&lt;message id u8&gt;&lt;payload&gt;; length=0 is keep-alive.
 */
public sealed interface PeerMessage {

    /** Upper bound on a single message length (max block 128 KiB + header margin); exceeding it is a protocol error. */
    int MAX_MESSAGE_LENGTH = 128 * 1024 + 64;

    record KeepAlive() implements PeerMessage {
    }

    /** id=0: we refuse to respond to the peer's requests. */
    record Choke() implements PeerMessage {
    }

    /** id=1: allow the peer to make requests. */
    record Unchoke() implements PeerMessage {
    }

    /** id=2: interested in pieces the peer holds. */
    record Interested() implements PeerMessage {
    }

    /** id=3. */
    record NotInterested() implements PeerMessage {
    }

    /** id=4: announces acquisition of a complete and verified piece. */
    record Have(int pieceIndex) implements PeerMessage {
    }

    /** id=5: the first message after the handshake, announcing the set of pieces held. */
    record BitfieldMessage(Bitfield bitfield) implements PeerMessage {
    }

    /** id=6: requests a block (length conventionally 16 KiB, must not exceed 128 KiB). */
    record Request(int pieceIndex, int begin, int length) implements PeerMessage {
    }

    /** id=7: block data. */
    record Piece(int pieceIndex, int begin, byte[] data) implements PeerMessage {
    }

    /** id=8: cancels a previous request (used in endgame). */
    record Cancel(int pieceIndex, int begin, int length) implements PeerMessage {
    }

    /** id=9: advertises our DHT UDP port (BEP 5, when reserved bit 0x01 is set). */
    record Port(int dhtPort) implements PeerMessage {
    }

    /** id=20: extension message (BEP 10). extendedId=0 is the extension handshake. */
    record Extended(int extendedId, byte[] payload) implements PeerMessage {
    }

    // --- Fast Extension (BEP 6, usable only when reserved 0x04 is set) ---

    /** id=0x0D: suggest piece. */
    record SuggestPiece(int pieceIndex) implements PeerMessage {
    }

    /** id=0x0E: equivalent to a bitfield holding all pieces. */
    record HaveAll() implements PeerMessage {
    }

    /** id=0x0F: equivalent to an all-empty bitfield. */
    record HaveNone() implements PeerMessage {
    }

    /** id=0x10: explicitly rejects a request (instead of silently not responding). */
    record RejectRequest(int pieceIndex, int begin, int length) implements PeerMessage {
    }

    /** id=0x11: a piece the peer is allowed to download even while choked. */
    record AllowedFast(int pieceIndex) implements PeerMessage {
    }

    // ---- encode ----

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

    // ---- decode ----

    /**
     * Reads the next message (blocking). Unknown message ids are skipped entirely and reading continues
     * (for compatibility); an abnormal length or end of stream throws IOException.
     *
     * @param pieceCount used to validate the bitfield length
     */
    static PeerMessage read(DataInputStream in, int pieceCount) throws IOException {
        while (true) {
            int length = in.readInt();
            if (length == 0) {
                return new KeepAlive();
            }
            if (length < 0 || length > MAX_MESSAGE_LENGTH) {
                throw new IOException("abnormal message length: " + length);
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
                // pieceCount <= 0 (magnet case, metadata unknown): leniently parse using the payload bit count as the piece count
                case 5 -> new BitfieldMessage(Bitfield.fromBytes(payload,
                        pieceCount > 0 ? pieceCount : payload.length * 8));
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
                default -> null; // unknown id: skipped by the caller
            };
        } catch (java.nio.BufferUnderflowException | IllegalArgumentException e) {
            throw new IOException("malformed payload for message id=" + id, e);
        }
    }
}
