package net.derrek.bt4j.metainfo;

import java.util.List;

/**
 * torrent 內的單一檔案。
 *
 * @param index  在 torrent 中的序號（0-based），UI 勾選檔案與 DownloadPlan 以此為準
 * @param path   相對路徑（單檔 torrent 為 [name]，多檔為 [name, dir..., file]）
 * @param length 檔案長度（bytes）
 * @param offset 此檔案在「全 torrent 連續位元組空間」的起始位移，用於 piece ↔ 檔案對映
 */
public record FileEntry(int index, List<String> path, long length, long offset) {

    /** 以 '/' 串接的顯示用相對路徑。 */
    public String displayPath() {
        return String.join("/", path);
    }
}
