package net.derrek.bt4j.tracker;

/** tracker announce 的 event 參數（BEP 3）。 */
public enum AnnounceEvent {
    /** 開始下載（第一次 announce）。 */
    STARTED,
    /** 停止（關閉上傳、關閉 session 時送出）。 */
    STOPPED,
    /** 下載完成。 */
    COMPLETED,
    /** 定期回報（不帶 event 參數）。 */
    NONE
}
