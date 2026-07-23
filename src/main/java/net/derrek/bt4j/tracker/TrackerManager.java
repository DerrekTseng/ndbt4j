package net.derrek.bt4j.tracker;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import net.derrek.bt4j.peer.PeerAddress;

/**
 * 單一 torrent 的 announce 排程（BEP 12 multitracker）：
 * 每輪依 tier 順序嘗試，tier 內成功者晉升到最前（下輪優先）；
 * 全部失敗以固定間隔重試。started/completed/stopped 事件由生命週期方法觸發。
 */
public final class TrackerManager implements AutoCloseable {

    /** tracker 回報 interval 的上限（保持找 peer 的靈敏度）。 */
    private static final Duration MAX_INTERVAL = Duration.ofMinutes(5);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);

    private final List<List<Tracker>> tiers;
    private final Function<AnnounceEvent, AnnounceRequest> requests;
    private final Consumer<List<PeerAddress>> onPeersFound;

    private volatile Thread thread;
    private volatile boolean closed;
    private volatile boolean completedPending;
    private boolean startedSent;

    /**
     * @param tiers        BEP 12 的 tracker 分層；每層在建構時洗牌一次（BEP 12 規定）
     * @param requests     依事件產生 announce 參數（由 session 提供目前的統計）
     * @param onPeersFound 每次 announce 取得的 peer 清單回呼
     */
    public TrackerManager(List<List<Tracker>> tiers,
                          Function<AnnounceEvent, AnnounceRequest> requests,
                          Consumer<List<PeerAddress>> onPeersFound) {
        List<List<Tracker>> copy = new ArrayList<>();
        for (List<Tracker> tier : tiers) {
            List<Tracker> shuffled = new ArrayList<>(tier);
            Collections.shuffle(shuffled);
            copy.add(shuffled);
        }
        this.tiers = copy;
        this.requests = requests;
        this.onPeersFound = onPeersFound;
    }

    /** 開始週期 announce（第一次成功的 announce 帶 started）。 */
    public synchronized void start() {
        if (thread != null) {
            throw new IllegalStateException("已啟動");
        }
        thread = Thread.ofVirtual().name("bt4j-tracker-manager").start(this::loop);
    }

    /** 下載完成：盡快對 tracker 發 completed。 */
    public void announceCompleted() {
        completedPending = true;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    /** 停止排程；已送過 started 的話盡力發 stopped（在排程 thread 上，不阻塞呼叫端）。 */
    @Override
    public void close() {
        closed = true;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    private void loop() {
        while (!closed) {
            AnnounceEvent event = !startedSent ? AnnounceEvent.STARTED
                    : completedPending ? AnnounceEvent.COMPLETED
                    : AnnounceEvent.NONE;
            Duration interval = announceOnce(event);
            if (interval != null) {
                startedSent = true;
                if (event == AnnounceEvent.COMPLETED) {
                    completedPending = false;
                }
            }
            try {
                Duration wait = interval == null ? RETRY_DELAY
                        : interval.compareTo(MAX_INTERVAL) > 0 ? MAX_INTERVAL : interval;
                Thread.sleep(wait.toMillis());
            } catch (InterruptedException e) {
                // 喚醒原因：completed 待送（回到迴圈頂）或 close（迴圈條件退出）
            }
        }
        Thread.interrupted(); // 清旗標，避免 stopped announce 被立即中斷
        if (startedSent) {
            announceOnce(AnnounceEvent.STOPPED);
        }
    }

    /** 依 tier 順序嘗試一輪。成功回傳 interval 並將該 tracker 晉升至其 tier 最前；全失敗回傳 null。 */
    private Duration announceOnce(AnnounceEvent event) {
        for (List<Tracker> tier : tiers) {
            List<Tracker> snapshot;
            synchronized (tier) {
                snapshot = List.copyOf(tier);
            }
            for (Tracker tracker : snapshot) {
                try {
                    AnnounceResponse response = tracker.announce(requests.apply(event));
                    synchronized (tier) {
                        tier.remove(tracker);
                        tier.addFirst(tracker); // BEP 12：成功者下輪優先
                    }
                    if (!response.peers().isEmpty()) {
                        onPeersFound.accept(response.peers());
                    }
                    return response.interval();
                } catch (TrackerException | RuntimeException e) {
                    // 換下一個 tracker
                }
            }
        }
        return null;
    }
}
