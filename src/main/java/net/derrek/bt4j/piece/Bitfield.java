package net.derrek.bt4j.piece;

import java.util.Objects;

/**
 * piece 持有狀態的位元圖（BEP 3 bitfield 訊息的線上格式：MSB 在前，尾端補 0）。
 * 可變；跨執行緒使用時由呼叫端同步。
 */
public final class Bitfield {

    private final byte[] bits;
    private final int pieceCount;
    private int cardinality;

    public Bitfield(int pieceCount) {
        if (pieceCount <= 0) {
            throw new IllegalArgumentException("piece 數必須為正: " + pieceCount);
        }
        this.pieceCount = pieceCount;
        this.bits = new byte[(pieceCount + 7) / 8];
    }

    private Bitfield(byte[] bits, int pieceCount, int cardinality) {
        this.bits = bits;
        this.pieceCount = pieceCount;
        this.cardinality = cardinality;
    }

    /** 從線上格式解析。長度必須恰好，且尾端 padding bit 必須為 0，否則視為協定錯誤。 */
    public static Bitfield fromBytes(byte[] wireFormat, int pieceCount) {
        int expected = (pieceCount + 7) / 8;
        if (wireFormat.length != expected) {
            throw new IllegalArgumentException("bitfield 長度應為 " + expected + " bytes，收到 " + wireFormat.length);
        }
        int paddingBits = expected * 8 - pieceCount;
        if (paddingBits > 0 && (wireFormat[expected - 1] & ((1 << paddingBits) - 1)) != 0) {
            throw new IllegalArgumentException("bitfield 尾端 padding bit 必須為 0");
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

    /** 已設定的 piece 數。 */
    public int cardinality() {
        return cardinality;
    }

    public int pieceCount() {
        return pieceCount;
    }

    public boolean isComplete() {
        return cardinality == pieceCount;
    }

    /** 序列化為線上格式。 */
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
