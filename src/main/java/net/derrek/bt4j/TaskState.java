package net.derrek.bt4j;

/**
 * 下載任務的對外狀態（facade 層；不對外暴露 metadata 取得等內部階段）。
 */
public enum TaskState {
    /** 下載中。 */
    DOWNLOADING,
    /** 下載完成、持續上傳（做種）。 */
    SEEDING,
    /** 已停止（手動 stop）。 */
    STOPPED,
    /** 發生不可恢復的錯誤。 */
    ERROR
}
