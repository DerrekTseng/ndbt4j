package net.derrek.bt4j.piece;

import java.util.Objects;

/**
 * Bitmap of piece-possession state (BEP 3 bitfield message wire format: MSB first, zero-padded at the end).
 * Mutable; the caller synchronizes when used across threads.
 */
public final class Bitfield {

    private final byte[] bits;
    private final int pieceCount;
    private int cardinality;

    public Bitfield(int pieceCount) {
        if (pieceCount <= 0) {
            throw new IllegalArgumentException("piece count must be positive: " + pieceCount);
        }
        this.pieceCount = pieceCount;
        this.bits = new byte[(pieceCount + 7) / 8];
    }

    private Bitfield(byte[] bits, int pieceCount, int cardinality) {
        this.bits = bits;
        this.pieceCount = pieceCount;
        this.cardinality = cardinality;
    }

    /** Parse from wire format. Length must match exactly and trailing padding bits must be 0, otherwise it is treated as a protocol error. */
    public static Bitfield fromBytes(byte[] wireFormat, int pieceCount) {
        int expected = (pieceCount + 7) / 8;
        if (wireFormat.length != expected) {
            throw new IllegalArgumentException("bitfield length should be " + expected + " bytes, got " + wireFormat.length);
        }
        int paddingBits = expected * 8 - pieceCount;
        if (paddingBits > 0 && (wireFormat[expected - 1] & ((1 << paddingBits) - 1)) != 0) {
            throw new IllegalArgumentException("bitfield trailing padding bits must be 0");
        }
        int cardinality = 0;
        for (byte b : wireFormat) {
            cardinality += Integer.bitCount(b & 0xFF);
        }
        return new Bitfield(wireFormat.clone(), pieceCount, cardinality);
    }

    public boolean get(int pieceIndex) {
        Objects.checkIndex(pieceIndex, pieceCount);
        return (bits[pieceIndex / 8] & (1 << (7 - (pieceIndex % 8)))) != 0;
    }

    public void set(int pieceIndex) {
        Objects.checkIndex(pieceIndex, pieceCount);
        if (!get(pieceIndex)) {
            bits[pieceIndex / 8] |= (byte) (1 << (7 - (pieceIndex % 8)));
            cardinality++;
        }
    }

    public void setAll() {
        for (int i = 0; i < pieceCount; i++) {
            set(i);
        }
    }

    /** Clears a single piece bit (used when a mid-download re-selection revokes a completed boundary piece). */
    public void clear(int pieceIndex) {
        Objects.checkIndex(pieceIndex, pieceCount);
        if (get(pieceIndex)) {
            bits[pieceIndex / 8] &= (byte) ~(1 << (7 - (pieceIndex % 8)));
            cardinality--;
        }
    }

    /** Number of pieces set. */
    public int cardinality() {
        return cardinality;
    }

    public int pieceCount() {
        return pieceCount;
    }

    public boolean isComplete() {
        return cardinality == pieceCount;
    }

    /** Serialize to wire format. */
    public byte[] toBytes() {
        return bits.clone();
    }

    public Bitfield copy() {
        return new Bitfield(bits.clone(), pieceCount, cardinality);
    }

    @Override
    public String toString() {
        return "Bitfield[" + cardinality + "/" + pieceCount + "]";
    }
}
