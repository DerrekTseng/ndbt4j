package net.derrek.bt4j.tracker;

import java.net.URI;

/**
 * 單一 tracker。實作：HttpTracker（http/https）、UdpTracker（udp）。
 * 實作必須是執行緒安全的（announce 可能由不同 virtual thread 呼叫）。
 */
public interface Tracker {

    URI uri();

    /**
     * 同步 announce（阻塞呼叫，配合 virtual thread 使用）。
     *
     * @throws TrackerException 連線失敗、逾時、tracker 回傳 failure reason
     */
    AnnounceResponse announce(AnnounceRequest request) throws TrackerException;

    /** 依 URI scheme 建立對應實作。 */
    static Tracker of(URI uri) {
        throw new UnsupportedOperationException("尚未實作");
    }
}
