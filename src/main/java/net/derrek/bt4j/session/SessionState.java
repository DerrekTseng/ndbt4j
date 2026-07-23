package net.derrek.bt4j.session;

/**
 * TorrentSession 狀態機。
 * <pre>
 * addMagnet ─→ FETCHING_METADATA ─→ METADATA_READY ─ start() ─→ DOWNLOADING ─→ SEEDING
 * addTorrent ─────────────────────→       │                        │              │
 *                                          └── close() ──→ STOPPED ←─ stopSeeding()┘
 * 任何狀態發生不可恢復錯誤 ─→ ERROR
 * </pre>
 */
public enum SessionState {
    /** 磁力連結：正在向 swarm 取得 metadata。 */
    FETCHING_METADATA,
    /** metadata 已就緒，等待上層呼叫 start()（UI 勾選檔案階段）。 */
    METADATA_READY,
    DOWNLOADING,
    /** 下載完成，持續上傳。 */
    SEEDING,
    /** 已停止（stopSeeding 或 close）。 */
    STOPPED,
    ERROR
}
