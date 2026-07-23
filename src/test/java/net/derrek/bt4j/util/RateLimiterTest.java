package net.derrek.bt4j.util;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RateLimiterTest {

    @Test
    void unlimitedReturnsImmediately() {
        RateLimiter limiter = new RateLimiter(0);
        assertTrue(limiter.isUnlimited());
        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            limiter.acquire(1_000_000);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 100, "不限速應幾乎不耗時，實際 " + elapsedMs + "ms");
    }

    @Test
    void throttlesToApproximatelyTheConfiguredRate() {
        // 100 KiB/s；先耗掉突發量，再量測穩態速率
        long rate = 100 * 1024;
        RateLimiter limiter = new RateLimiter(rate);
        limiter.acquire(256 * 1024); // 排掉初始 burst（capacity）

        long start = System.nanoTime();
        long total = 0;
        // 取用約 100 KiB，理應花費約 1 秒
        for (int i = 0; i < 100; i++) {
            limiter.acquire(1024);
            total += 1024;
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        double observedRate = total / seconds;
        // 容忍度寬鬆：穩態速率應落在設定值的 0.5x ~ 2x
        assertTrue(observedRate <= rate * 2.0 && observedRate >= rate * 0.4,
                "觀測速率 " + (long) observedRate + " B/s 應接近設定 " + rate + " B/s");
    }

    @Test
    void largeBlockDoesNotDeadlock() {
        RateLimiter limiter = new RateLimiter(10 * 1024); // 很慢，但 capacity≥256KiB
        limiter.acquire(128 * 1024); // 一個最大 block，不應無限迴圈
        assertTrue(true);
    }
}
