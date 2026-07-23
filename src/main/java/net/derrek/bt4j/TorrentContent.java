package net.derrek.bt4j;

import java.util.List;

/**
 * 一個 torrent 的內容描述（名稱、檔案清單、總大小）。
 * 來源：{@link Bt#fromTorrent} 直接解析，或 {@link Bt#fromMagnet} 從 swarm 取得 metadata 後。
 * 取得後即為純資料，不佔用網路資源。
 */
public interface TorrentContent {

    /** 40 字元小寫 hex 的 info-hash。 */
    String infoHashHex();

    /** torrent 名稱。 */
    String name();

    /** 所有檔案，依 torrent 內順序。UI 可對此過濾出要下載的檔案。 */
    List<TorrentContentFile> getFileList();

    /** 全部檔案總大小（bytes）。 */
    long totalSize();
}
