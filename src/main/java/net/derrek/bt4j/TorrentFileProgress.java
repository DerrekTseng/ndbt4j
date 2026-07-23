package net.derrek.bt4j;

/**
 * 單一檔案的下載進度（{@link TorrentDownloadTask#fileProgress()} 的元素）。
 * 全 getter、可安全輪詢。
 *
 * 註：跨檔案共用的邊界 piece 完成前，兩側檔案的進度都可能略有延後，屬正常。
 */
public interface TorrentFileProgress {

    /** 對應的檔案。 */
    TorrentContentFile file();

    /** 此檔已下載並驗證的位元組數。 */
    long downloadedBytes();

    /** 此檔總大小（= file().size()）。 */
    long totalBytes();

    /** 0.0 ~ 1.0。 */
    double progress();

    /** 此檔是否全部完成。 */
    boolean completed();
}
