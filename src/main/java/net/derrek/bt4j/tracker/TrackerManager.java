package net.derrek.bt4j.tracker;

import java.util.List;
import java.util.function.Consumer;
import net.derrek.bt4j.peer.PeerAddress;

/**
 * 單一 torrent 的 announce 排程：管理多層 tracker（BEP 12 tiers）、
 * 依 interval 週期 announce、失敗換下一個 tracker、將新 peer 送給回呼。
 * 由 session 內部使用。
 */
public final class TrackerManager implements AutoCloseable {

    /**
     * @param tiers       BEP 12 的 tracker 分層（同層隨機、失敗才用下一層）
     * @param onPeersFound 每次 announce 取得的 peer 清單回呼
     */
    public TrackerManager(List<List<Tracker>> tiers, Consumer<List<PeerAddress>> onPeersFound) {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** 開始週期 announce（送 STARTED）。 */
    public void start() {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** 立即對所有 tier 發 COMPLETED。 */
    public void announceCompleted() {
        throw new UnsupportedOperationException("尚未實作");
    }

    /** 停止排程並發 STOPPED（關閉上傳時呼叫）。 */
    @Override
    public void close() {
        throw new UnsupportedOperationException("尚未實作");
    }
}
