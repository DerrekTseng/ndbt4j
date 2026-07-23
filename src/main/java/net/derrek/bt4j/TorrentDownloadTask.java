package net.derrek.bt4j;

import java.nio.file.Path;
import java.util.List;

/**
 * 執行中的下載/做種任務把手。**全部為 getter、執行緒安全、可持續輪詢**（快照式，無 setter）。
 * 生命週期控制在 {@link Bt}（stop / deleteJob）。
 */
public interface TorrentDownloadTask {

    /** 40 字元 hex info-hash。 */
    String infoHashHex();

    /** torrent 名稱。 */
    String name();

    /** 下載目標目錄。 */
    Path targetDirectory();

    TaskState state();

    // ---- 總體進度 ----

    /** 勾選範圍的總大小（bytes）——總進度的分母。 */
    long totalBytes();

    /** 已下載並驗證的位元組數（僅計勾選範圍）。 */
    long downloadedBytes();

    /** 累計上傳位元組數。 */
    long uploadedBytes();

    /** 總體進度 0.0 ~ 1.0。 */
    double progress();

    /** 近期下載速率（bytes/s）。 */
    long downloadRate();

    /** 近期上傳速率（bytes/s）。 */
    long uploadRate();

    /** 目前連線的 peer 數。 */
    int connectedPeers();

    // ---- 逐檔進度 ----

    /** 各勾選檔案的進度。 */
    List<TorrentFileProgress> fileProgress();
}
