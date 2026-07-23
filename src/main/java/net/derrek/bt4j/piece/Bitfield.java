package net.derrek.bt4j.piece;

/**
 * piece 持有狀態的位元圖（BEP 3 bitfield 訊息的線上格式：MSB 在前，尾端補 0）。
 * 可變；跨執行緒使用時由呼叫端同步。
 */
public final class Bitfield {

    public Bitfield(int pieceCount) {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** 從線上格式解析。尾端 padding bit 必須為 0，否則視為協定錯誤。 */
    public static Bitfield fromBytes(byte[] wireFormat, int pieceCount) {
        throw new UnsupportedOperationException("尚未實作");
    }

    public boolean get(int pieceIndex) {
        throw new UnsupportedOperationException("尚未實作");
    }

    public void set(int pieceIndex) {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** 已設定的 piece 數。 */
    public int cardinality() {
        throw new UnsupportedOperationException("尚未實作");
    }

    public int pieceCount() {
        throw new UnsupportedOperationException("尚未實作");
    }

    public boolean isComplete() {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** 序列化為線上格式。 */
    public byte[] toBytes() {
        throw new UnsupportedOperationException("尚未實作");
    }

    public Bitfield copy() {
        throw new UnsupportedOperationException("尚未實作");
    }
}
