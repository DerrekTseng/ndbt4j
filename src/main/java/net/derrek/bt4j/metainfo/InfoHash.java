package net.derrek.bt4j.metainfo;

/**
 * torrent 的識別碼：info 字典原始位元組的 SHA-1（20 bytes，BitTorrent v1）。
 */
public record InfoHash(byte[] bytes) {

    public InfoHash {
        if (bytes.length != 20) {
            throw new IllegalArgumentException("info-hash 必須是 20 bytes，收到 " + bytes.length);
        }
    }

    /** 40 字元小寫 hex，即磁力連結 xt=urn:btih: 後的形式。 */
    public String hex() {
        throw new UnsupportedOperationException("尚未實作");
    }

    public static InfoHash fromHex(String hex40) {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** 對 info 字典的原始位元組計算 SHA-1。 */
    public static InfoHash ofInfoDict(byte[] infoDictBytes) {
        throw new UnsupportedOperationException("尚未實作");
    }

    // record 對 byte[] 預設是參考比較，須以內容比較（InfoHash 會作為 Map key）
    @Override
    public boolean equals(Object o) {
        return o instanceof InfoHash other && java.util.Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return java.util.Arrays.hashCode(bytes);
    }
}
