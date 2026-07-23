package net.derrek.bt4j.util;

/**
 * Token bucket rate limiter (bytes/s). Thread-safe, intended for blocking use with virtual threads:
 * {@link #acquire(int)} sleeps until enough tokens are available before returning, producing TCP backpressure by deferring reads and writes.
 *
 * Rate semantics:
 * <ul>
 *   <li>{@code < 0}: unlimited ({@link #isUnlimited()}; acquire returns immediately)</li>
 *   <li>{@code == 0}: blocked ({@link #isBlocked()}; intercepted by the caller before acquire, which should not actually be called)</li>
 *   <li>{@code > 0}: throttled to that rate</li>
 * </ul>
 * Allows some burst (capacity ≥ 256 KiB), so that small blocks are not fragmented too finely and large blocks do not stall for lack of tokens.
 */
public final class RateLimiter {

    private final long ratePerSec;
    private final double capacity;
    private double tokens;
    private long lastRefillNanos;

    /** @param bytesPerSec bytes-per-second limit; {@code < 0} unlimited, {@code == 0} blocked, {@code > 0} throttled */
    public RateLimiter(long bytesPerSec) {
        this.ratePerSec = bytesPerSec;
        this.capacity = Math.max(bytesPerSec, 256 * 1024);
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    /** Unlimited (rate < 0). */
    public boolean isUnlimited() {
        return ratePerSec < 0;
    }

    /** Fully blocked (rate == 0). The caller should use this to skip transfers in that direction rather than calling acquire. */
    public boolean isBlocked() {
        return ratePerSec == 0;
    }

    /** Upper bound on a single sleep slice, so a cancelled waiter is noticed promptly even at very low rates. */
    private static final long MAX_SLICE_NANOS = 100_000_000L; // 100ms

    /**
     * Acquires {@code bytes} tokens (blocks and waits if there are not enough). A non-positive rate (unlimited or blocked) returns immediately—
     * whether it is blocked is intercepted in advance by the caller via {@link #isBlocked()}; acquire does not do the blocking.
     * If interrupted while waiting, restores the interrupt flag and returns early (letting the upper layer finish up based on the connection's closed state).
     */
    public void acquire(int bytes) {
        acquire(bytes, () -> false);
    }

    /**
     * Like {@link #acquire(int)}, but polls {@code cancelled} between bounded sleep slices and returns early
     * (without consuming tokens) once it turns true. Lets a waiter tied to a peer connection stop throttling
     * promptly when that connection closes — a socket close cannot wake a plain {@code Thread.sleep}, and peer
     * read threads are deliberately never interrupted (an interrupt during a shared-FileChannel write would
     * close the channel for every peer).
     */
    public void acquire(int bytes, java.util.function.BooleanSupplier cancelled) {
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
            long slice = Math.min(waitNanos, MAX_SLICE_NANOS);
            try {
                Thread.sleep(slice / 1_000_000L, (int) (slice % 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (cancelled.getAsBoolean()) {
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
