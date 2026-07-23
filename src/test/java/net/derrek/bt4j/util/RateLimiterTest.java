package net.derrek.bt4j.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RateLimiterTest {

    @Test
    void negativeMeansUnlimited() {
        RateLimiter limiter = new RateLimiter(-1);
        assertTrue(limiter.isUnlimited());
        assertFalse(limiter.isBlocked());
        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            limiter.acquire(1_000_000);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 100, "unlimited should take almost no time, actual " + elapsedMs + "ms");
    }

    @Test
    void zeroMeansBlocked() {
        RateLimiter limiter = new RateLimiter(0);
        assertTrue(limiter.isBlocked());
        assertFalse(limiter.isUnlimited());
    }

    @Test
    void throttlesToApproximatelyTheConfiguredRate() {
        // 100 KiB/s; first drain the burst, then measure the steady-state rate
        long rate = 100 * 1024;
        RateLimiter limiter = new RateLimiter(rate);
        limiter.acquire(256 * 1024); // drain the initial burst (capacity)

        long start = System.nanoTime();
        long total = 0;
        // acquire about 100 KiB, which should take about 1 second
        for (int i = 0; i < 100; i++) {
            limiter.acquire(1024);
            total += 1024;
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        double observedRate = total / seconds;
        // loose tolerance: the steady-state rate should fall within 0.5x ~ 2x of the configured value
        assertTrue(observedRate <= rate * 2.0 && observedRate >= rate * 0.4,
                "observed rate " + (long) observedRate + " B/s should be close to the configured " + rate + " B/s");
    }

    @Test
    void largeBlockDoesNotDeadlock() {
        RateLimiter limiter = new RateLimiter(10 * 1024); // very slow, but capacity≥256KiB
        limiter.acquire(128 * 1024); // a maximum-size block, should not loop forever
        assertTrue(true);
    }
}
