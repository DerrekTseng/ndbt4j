package net.derrek.bt4j.utp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** uTP (BEP 29) header codec. */
class UtpPacketTest {

    @Test
    void dataPacketRoundTrips() {
        byte[] payload = {1, 2, 3, 4, 5};
        UtpPacket p = new UtpPacket(UtpPacket.ST_DATA, 0xABCD, 0x11223344L, 0x55667788L,
                0x00010000L, 40000, 39999, null, payload);
        UtpPacket decoded = UtpPacket.decode(p.encode(), p.encode().length);
        assertEquals(UtpPacket.ST_DATA, decoded.type());
        assertEquals(0xABCD, decoded.connectionId());
        assertEquals(0x11223344L, decoded.timestampMicros());
        assertEquals(0x55667788L, decoded.timestampDiffMicros());
        assertEquals(0x00010000L, decoded.wndSize());
        assertEquals(40000, decoded.seqNr());
        assertEquals(39999, decoded.ackNr());
        assertNull(decoded.selectiveAck());
        assertArrayEquals(payload, decoded.payload());
    }

    @Test
    void statePacketWithSelectiveAckRoundTrips() {
        byte[] sack = {0b0000_0101, 0, 0, 0}; // packets ack_nr+2 and ack_nr+4 received
        UtpPacket p = new UtpPacket(UtpPacket.ST_STATE, 1, 100, 0, 4096, 5, 4, sack, null);
        byte[] wire = p.encode();
        UtpPacket decoded = UtpPacket.decode(wire, wire.length);
        assertEquals(UtpPacket.ST_STATE, decoded.type());
        assertArrayEquals(sack, decoded.selectiveAck());
        assertEquals(0, decoded.payload().length);
    }

    @Test
    void unknownExtensionsAreSkipped() {
        // hand-build a header with an unknown extension (id 42, len 4) before the payload
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(UtpPacket.HEADER_SIZE + 6 + 2);
        buf.put((byte) ((UtpPacket.ST_DATA << 4) | UtpPacket.VERSION));
        buf.put((byte) 42);                 // first extension id
        buf.putShort((short) 7);            // connection id
        buf.putInt(0).putInt(0).putInt(0);  // timestamps + wnd
        buf.putShort((short) 9).putShort((short) 8); // seq, ack
        buf.put((byte) 0).put((byte) 4).put(new byte[4]); // ext: next=0, len=4, data
        buf.put(new byte[]{42, 43});        // payload
        UtpPacket decoded = UtpPacket.decode(buf.array(), buf.position());
        assertEquals(7, decoded.connectionId());
        assertArrayEquals(new byte[]{42, 43}, decoded.payload());
    }

    @Test
    void rejectsWrongVersionAndTooShort() {
        assertNull(UtpPacket.decode(new byte[]{(byte) (0 << 4 | 2)}, 1)); // too short
        byte[] wrongVersion = new byte[UtpPacket.HEADER_SIZE];
        wrongVersion[0] = (byte) ((UtpPacket.ST_DATA << 4) | 2); // version 2
        assertNull(UtpPacket.decode(wrongVersion, wrongVersion.length));
    }

    @Test
    void sequenceComparisonHandlesWraparound() {
        assertTrue(UtpPacket.seqLess(65535, 0));   // 65535 is just before 0
        assertTrue(UtpPacket.seqLess(0, 1));
        assertFalse(UtpPacket.seqLess(1, 0));
        assertFalse(UtpPacket.seqLess(5, 5));
        assertEquals(1, UtpPacket.seqDistance(0, 65535));
        assertEquals(-1, UtpPacket.seqDistance(65535, 0));
    }
}
