package net.derrek.bt4j.util;

/**
 * Token bucket 限速器（bytes/s）。執行緒安全，配合 virtual thread 阻塞式使用：
 * {@link #acquire(int)} 會 sleep 到有足夠 token 才返回，藉由延後讀寫產生 TCP 背壓。
 *
 * 速率語意：
 * <ul>
 *   <li>{@code < 0}：不限速（{@link #isUnlimited()}；acquire 立即返回）</li>
 *   <li>{@code == 0}：封鎖（{@link #isBlocked()}；由呼叫端在 acquire 之前攔截，不應真的呼叫 acquire）</li>
 *   <li>{@code > 0}：限制在該速率</li>
 * </ul>
 * 允許一段突發量（capacity ≥ 256 KiB），以免每個小 block 被切得太碎、或大 block 湊不滿而卡死。
 */
public final class RateLimiter {

    private final long ratePerSec;
    private final double capacity;
    private double tokens;
    private long lastRefillNanos;

    /** @param bytesPerSec 每秒位元組上限；{@code < 0} 不限速、{@code == 0} 封鎖、{@code > 0} 限速 */
    public RateLimiter(long bytesPerSec) {
        this.ratePerSec = bytesPerSec;
        this.capacity = Math.max(bytesPerSec, 256 * 1024);
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    /** 不限速（rate < 0）。 */
    public boolean isUnlimited() {
        return ratePerSec < 0;
    }

    /** 完全封鎖（rate == 0）。呼叫端應據此不進行該方向的傳輸，而非呼叫 acquire。 */
    public boolean isBlocked() {
        return ratePerSec == 0;
    }

    /**
     * 取得 bytes 個 token（不足則阻塞等待）。非正速率（不限速或封鎖）立即返回——
     * 封鎖與否由呼叫端以 {@link #isBlocked()} 事先攔截，acquire 不負責阻擋。
     * 若等待時被中斷，還原中斷旗標並提前返回（讓上層依連線關閉狀態收尾）。
     */
    public void acquire(int bytes) {
        if (ratePerSec <= 0 || bytes <= 0) {
            return;
        }
        while (true) {
            long waitNanos;
            synchronized (this) {
                refill();
                if (tokens >= bytes) {
                    tokens -= bytes;
                    return;
                }
                waitNanos = (long) Math.ceil((bytes - tokens) / (double) ratePerSec * 1_000_000_000L);
            }
            try {
                Thread.sleep(waitNanos / 1_000_000L, (int) (waitNanos % 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsedSec = (now - lastRefillNanos) / 1_000_000_000.0;
        tokens = Math.min(capacity, tokens + elapsedSec * ratePerSec);
        lastRefillNanos = now;
    }
}
