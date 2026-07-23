package net.derrek.bt4j;

import java.nio.file.Path;
import java.util.List;

/**
 * 一個下載任務的持久描述，對應目標目錄下的 {@code .bt4j} 檔。
 *
 * 由 {@link Bt#createDownloadJob}（新任務，當場建立 .bt4j）或
 * {@link Bt#restoreDownloadJob}（讀取既有 .bt4j 續傳）產生。
 * 傳給 {@link Bt#download} 才真正開始執行。
 */
public interface TorrentDownloadJob {

    /** 所屬 torrent 內容。 */
    TorrentContent content();

    /** 下載目標目錄（.bt4j 檔即位於此）。 */
    Path targetDirectory();

    /** 勾選要下載的檔案（restore 時由 .bt4j 還原）。 */
    List<TorrentContentFile> selectedFiles();

    /** 下載完成後是否移入做種列表。 */
    boolean seedAfterComplete();

    /** 已完成並驗證的位元組數（新任務為 0；restore 為既有進度）。 */
    long completedBytes();
}
