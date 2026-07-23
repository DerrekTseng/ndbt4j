package net.derrek.bt4j.session;

import net.derrek.bt4j.metainfo.FileEntry;
import net.derrek.bt4j.metainfo.Metainfo;

/**
 * session 事件回呼（推播模型；輪詢請用 {@link TorrentSession#stats()}）。
 * 所有方法都有空的預設實作，實作端只覆寫需要的事件。
 * 回呼在套件內部 thread 執行，不可長時間阻塞。
 */
public interface SessionListener {

    default void onStateChanged(TorrentSession session, SessionState oldState, SessionState newState) {
    }

    /** 磁力連結取得 metadata（此時 UI 可顯示檔案清單）。 */
    default void onMetadataReady(TorrentSession session, Metainfo metainfo) {
    }

    /** 單一檔案的所有 piece 完成。 */
    default void onFileCompleted(TorrentSession session, FileEntry file) {
    }

    /** 全部勾選檔案下載完成（進入 SEEDING 或 STOPPED）。 */
    default void onDownloadCompleted(TorrentSession session) {
    }

    default void onError(TorrentSession session, Throwable error) {
    }
}
