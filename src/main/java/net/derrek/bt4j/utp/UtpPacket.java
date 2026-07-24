package net.derrek.bt4j.utp;

import java.nio.ByteBuffer;

/**
 * A uTP packet (BEP 29), version 1. Sequence/ack numbers count packets, not bytes. Timestamps are in
 * microseconds and wrap at 32 bits; sequence and ack numbers wrap at 16 bits — comparisons must be modular
 * (see {@link #seqLess}).
 *
 * <p>Fields that are unsigned on the wire are widened here (u16 → int, u32 → long) so ordinary Java arithmetic
 * behaves, and narrowed again on encode.
 */
record UtpPacket(int type, int connectionId, long timestampMicros, long timestampDiffMicros,
                 long wndSize, int seqNr, int ackNr, byte[] selectiveAck, byte[] payload) {

    static final int ST_DATA = 0;
    static final int ST_FIN = 1;
    static final int ST_STATE = 2;
    static final int ST_RESET = 3;
    static final int ST_SYN = 4;

    static final int VERSION = 1;
    static final int HEADER_SIZE = 20;
    static final int EXT_SELECTIVE_ACK = 1;

    static final byte[] NO_PAYLOAD = new byte[0];

    UtpPacket {
        if (payload == null) {
            payload = NO_PAYLOAD;
        }
    }

    /** Encodes the packet to wire bytes. */
    byte[] encode() {
        int extBytes = selectiveAck != null ? 2 + selectiveAck.length : 0;
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + extBytes + payload.length);
        buf.put((byte) ((type << 4) | VERSION));
        buf.put((byte) (selectiveAck != null ? EXT_SELECTIVE_ACK : 0));
        buf.putShort((short) connectionId);
        buf.putInt((int) timestampMicros);
        buf.putInt((int) timestampDiffMicros);
        buf.putInt((int) wndSize);
        buf.putShort((short) seqNr);
        buf.putShort((short) ackNr);
        if (selectiveAck != null) {
            buf.put((byte) 0);                    // next extension: none
            buf.put((byte) selectiveAck.length);
            buf.put(selectiveAck);
        }
        buf.put(payload);
        return buf.array();
    }

    /** Decodes a packet; returns null if the buffer is too short or the version is not 1. */
    static UtpPacket decode(byte[] data, int length) {
        if (length < HEADER_SIZE) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        int typeVer = buf.get() & 0xFF;
        int version = typeVer & 0x0F;
        int type = typeVer >>> 4;
        if (version != VERSION || type > ST_SYN) {
            return null;
        }
        int extension = buf.get() & 0xFF;
        int connectionId = buf.getShort() & 0xFFFF;
        long timestamp = buf.getInt() & 0xFFFFFFFFL;
        long timestampDiff = buf.getInt() & 0xFFFFFFFFL;
        long wndSize = buf.getInt() & 0xFFFFFFFFL;
        int seqNr = buf.getShort() & 0xFFFF;
        int ackNr = buf.getShort() & 0xFFFF;

        byte[] selectiveAck = null;
        // Walk the extension linked list, keeping the selective-ack payload and skipping anything unknown.
        while (extension != 0) {
            if (buf.remaining() < 2) {
                return null;
            }
            int next = buf.get() & 0xFF;
            int len = buf.get() & 0xFF;
            if (buf.remaining() < len) {
                return null;
            }
            byte[] extData = new byte[len];
            buf.get(extData);
            if (extension == EXT_SELECTIVE_ACK) {
                selectiveAck = extData;
            }
            extension = next;
        }
        byte[] payload = new byte[buf.remaining()];
        buf.get(payload);
        return new UtpPacket(type, connectionId, timestamp, timestampDiff, wndSize, seqNr, ackNr, selectiveAck, payload);
    }

    /** True when 16-bit sequence number {@code a} is strictly before {@code b}, accounting for wraparound. */
    static boolean seqLess(int a, int b) {
        return (short) (a - b) < 0;
    }

    /** Signed 16-bit distance {@code a - b}. */
    static int seqDistance(int a, int b) {
        return (short) (a - b);
    }

    boolean isData() {
        return type == ST_DATA;
    }
}
