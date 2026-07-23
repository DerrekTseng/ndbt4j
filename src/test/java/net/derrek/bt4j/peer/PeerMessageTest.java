package net.derrek.bt4j.peer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import net.derrek.bt4j.piece.Bitfield;
import org.junit.jupiter.api.Test;

class PeerMessageTest {

    private static byte[] encode(PeerMessage... messages) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        for (PeerMessage m : messages) {
            PeerMessage.write(out, m);
        }
        return bytes.toByteArray();
    }

    private static DataInputStream stream(byte[] data) {
        return new DataInputStream(new ByteArrayInputStream(data));
    }

    @Test
    void roundTripAllMessageTypes() throws IOException {
        Bitfield bf = new Bitfield(10);
        bf.set(2);
        List<PeerMessage> all = List.of(
                new PeerMessage.KeepAlive(),
                new PeerMessage.Choke(),
                new PeerMessage.Unchoke(),
                new PeerMessage.Interested(),
                new PeerMessage.NotInterested(),
                new PeerMessage.Have(42),
                new PeerMessage.BitfieldMessage(bf),
                new PeerMessage.Request(1, 16384, 16384),
                new PeerMessage.Piece(1, 16384, new byte[] {1, 2, 3}),
                new PeerMessage.Cancel(1, 16384, 16384),
                new PeerMessage.Port(6881),
                new PeerMessage.SuggestPiece(7),
                new PeerMessage.HaveAll(),
                new PeerMessage.HaveNone(),
                new PeerMessage.RejectRequest(2, 0, 16384),
                new PeerMessage.AllowedFast(3),
                new PeerMessage.Extended(1, new byte[] {9, 8}));

        DataInputStream in = stream(encode(all.toArray(PeerMessage[]::new)));
        for (PeerMessage expected : all) {
            PeerMessage actual = PeerMessage.read(in, 10);
            switch (expected) {
                case PeerMessage.BitfieldMessage(Bitfield b) -> {
                    PeerMessage.BitfieldMessage m = assertInstanceOf(PeerMessage.BitfieldMessage.class, actual);
                    assertArrayEquals(b.toBytes(), m.bitfield().toBytes());
                }
                case PeerMessage.Piece(int p, int begin, byte[] data) -> {
                    PeerMessage.Piece m = assertInstanceOf(PeerMessage.Piece.class, actual);
                    assertEquals(p, m.pieceIndex());
                    assertEquals(begin, m.begin());
                    assertArrayEquals(data, m.data());
                }
                case PeerMessage.Extended(int id, byte[] payload) -> {
                    PeerMessage.Extended m = assertInstanceOf(PeerMessage.Extended.class, actual);
                    assertEquals(id, m.extendedId());
                    assertArrayEquals(payload, m.payload());
                }
                default -> assertEquals(expected, actual);
            }
        }
        assertEquals(0, in.available());
    }

    @Test
    void unknownMessageIdIsSkipped() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(3);      // a message with unknown id=99
        out.writeByte(99);
        out.writeShort(0x1234);
        PeerMessage.write(out, new PeerMessage.Have(5)); // followed by a normal message

        assertEquals(new PeerMessage.Have(5), PeerMessage.read(stream(bytes.toByteArray()), 10));
    }

    @Test
    void oversizedLengthIsRejected() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new DataOutputStream(bytes).writeInt(Integer.MAX_VALUE);
        assertThrows(IOException.class, () -> PeerMessage.read(stream(bytes.toByteArray()), 10));
    }

    @Test
    void truncatedStreamThrows() {
        assertThrows(IOException.class, () -> PeerMessage.read(stream(new byte[] {0, 0}), 10));
    }

    @Test
    void malformedPayloadThrows() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(2); // Have should have a 5-byte payload, only 1 given
        out.writeByte(4);
        out.writeByte(0);
        assertThrows(IOException.class, () -> PeerMessage.read(stream(bytes.toByteArray()), 10));
    }

    @Test
    void keepAliveIsZeroLength() throws IOException {
        byte[] encoded = encode(new PeerMessage.KeepAlive());
        assertArrayEquals(new byte[] {0, 0, 0, 0}, encoded);
        assertTrue(PeerMessage.read(stream(encoded), 10) instanceof PeerMessage.KeepAlive);
    }
}
