package net.derrek.bt4j.metainfo;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/**
 * torrent metadata（BEP 3 的 metainfo）。
 * 來源可以是 .torrent 檔，或磁力連結經 BEP 9 從 peer 取得的 info 字典。
 * 不可變。
 */
public final class Metainfo {

    private Metainfo() {
    }

    /** 解析 .torrent 檔內容。 */
    public static Metainfo parse(byte[] torrentFileBytes) {
        throw new UnsupportedOperationException("尚未實作");
    }

    public static Metainfo parse(Path torrentFile) {
        throw new UnsupportedOperationException("尚未實作");
    }

    /**
     * 從 BEP 9 取得的 info 字典原始位元組建立（磁力連結情境）。
     * 呼叫端須先驗證 SHA-1(infoDictBytes) == 磁力連結的 info-hash。
     *
     * @param trackers 磁力連結 tr= 參數帶的 tracker，寫入 announce-list
     */
    public static Metainfo fromInfoDict(byte[] infoDictBytes, List<URI> trackers) {
        throw new UnsupportedOperationException("尚未實作");
    }

    public InfoHash infoHash() {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** torrent 名稱（info.name）。 */
    public String name() {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** 所有檔案，依 torrent 內順序；單檔 torrent 也統一以此表示。 */
    public List<FileEntry> files() {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** 全部檔案長度總和。 */
    public long totalLength() {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** 每個 piece 的長度（最後一個 piece 可能較短）。 */
    public long pieceLength() {
        throw new UnsupportedOperationException("尚未實作");
    }

    public int pieceCount() {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** 第 index 個 piece 的長度（處理最後一個 piece）。 */
    public int pieceLengthAt(int index) {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** 第 index 個 piece 的 SHA-1（20 bytes）。 */
    public byte[] pieceHash(int index) {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** tracker 清單。優先 announce-list（BEP 12），否則單一 announce。可能為空（純 DHT）。 */
    public List<List<URI>> announceList() {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** private flag（BEP 27）。true 時不得使用 DHT / PEX / LSD。 */
    public boolean isPrivate() {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** info 字典的原始位元組（BEP 9 回應 metadata request 時直接使用）。 */
    public byte[] infoDictBytes() {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** 序列化為標準 .torrent 檔內容（磁力連結取得 metadata 後可存檔）。 */
    public byte[] toTorrentBytes() {
        throw new UnsupportedOperationException("尚未實作");
    }

    public void saveTorrentFile(Path target) {
        throw new UnsupportedOperationException("尚未實作");
    }
}
