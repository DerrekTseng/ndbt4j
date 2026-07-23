package net.derrek.bt4j.bencode;

/**
 * bencoding 編碼／解碼入口（BEP 3）。純函式、無 IO。
 */
public final class Bencode {

    private Bencode() {
    }

    /**
     * 解碼完整的 bencoded 位元組。
     *
     * @throws BencodeException 格式錯誤，或 data 尾端有多餘位元組
     */
    public static BValue decode(byte[] data) {
        throw new UnsupportedOperationException("尚未實作");
    }

    /**
     * 從 data 的 offset 開始解碼單一值，並回報結束位置。
     * 用於需要取得原始位元組區段的情境（例如擷取 info 字典的原始 bytes 以計算 info-hash）。
     */
    public static DecodeResult decode(byte[] data, int offset) {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** 編碼為 bencoded 位元組。對同一棵值樹，輸出位元組具唯一性（canonical）。 */
    public static byte[] encode(BValue value) {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** decode(byte[], int) 的結果：解出的值與其在原始資料中的區段 [start, end)。 */
    public record DecodeResult(BValue value, int start, int end) {
    }
}
