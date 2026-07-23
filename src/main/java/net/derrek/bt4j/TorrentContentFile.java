package net.derrek.bt4j;

/**
 * torrent 內的一個檔案（供瀏覽與勾選；不含下載進度——進度在 {@link TorrentFileProgress}）。
 * 由 {@link TorrentContent#getFileList()} 取得，經過濾後傳給
 * {@link Bt#createDownloadJob}。同一個 {@link TorrentContent} 產生的檔案才能一起建立 job。
 */
public interface TorrentContentFile {

    /** 在 torrent 中的序號（0-based），用於指定下載哪些檔案。 */
    int index();

    /** 以 '/' 串接的相對路徑（多檔 torrent 含最上層目錄名）。 */
    String path();

    /** 檔案大小（bytes）。 */
    long size();

    /** 所屬的 torrent 內容（用於驗證同一種子）。 */
    TorrentContent content();
}
