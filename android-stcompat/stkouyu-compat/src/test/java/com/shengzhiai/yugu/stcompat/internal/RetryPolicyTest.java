package com.shengzhiai.yugu.stcompat.internal;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Back-off math of DESIGN 2.3 with deterministic random numbers. */
public class RetryPolicyTest {
    private final RetryPolicy p = RetryPolicy.DEFAULT;

    @Test
    public void defaults() {
        assertEquals(2, p.maxRetries);
        assertEquals(200, p.initialDelayMs);
        assertEquals(2.0, p.multiplier, 0);
        assertEquals(4000, p.maxDelayMs);
        assertEquals(0.3, p.jitter, 0);
        assertTrue(p.respectRetryAfter);
        assertEquals(30000, p.maxRetryAfterMs);
    }

    @Test
    public void baseDelaysAre200And400() {
        assertEquals(200.0, p.baseDelayMs(1), 0);
        assertEquals(400.0, p.baseDelayMs(2), 0);
        assertEquals(800.0, p.baseDelayMs(3), 0);
        assertEquals(4000.0, p.baseDelayMs(10), 0);
    }

    @Test
    public void jitterBoundsAreThirtyPercent() {
        // u = 0 maps to -jitter, u -> 1 maps to +jitter, u = 0.5 to no jitter
        assertEquals(140, p.delayMs(1, 0.0, -1));
        assertEquals(200, p.delayMs(1, 0.5, -1));
        assertEquals(260, p.delayMs(1, 0.9999999, -1));
        assertEquals(280, p.delayMs(2, 0.0, -1));
        assertEquals(520, p.delayMs(2, 0.9999999, -1));
        for (int i = 0; i < 1000; i++) {
            double u = i / 1000.0;
            long d = p.delayMs(1, u, -1);
            assertTrue(d >= 140 && d <= 260);
        }
    }

    @Test
    public void retryAfterIsRespectedAndCapped() {
        assertEquals(1000, p.delayMs(1, 0.5, 1000));
        assertEquals(200, p.delayMs(1, 0.5, 10));
        assertEquals(30000, p.delayMs(1, 0.5, 120000));
        RetryPolicy ignore = new RetryPolicy(2, 200, 2.0, 4000, 0.3, false, 30000);
        assertEquals(200, ignore.delayMs(1, 0.5, 5000));
    }

    @Test
    public void maxDelayCapsGrowth() {
        RetryPolicy r = new RetryPolicy(5, 1000, 3.0, 2500, 0, true, 30000);
        assertEquals(1000, r.delayMs(1, 0.3, -1));
        assertEquals(2500, r.delayMs(2, 0.3, -1));
        assertEquals(2500, r.delayMs(5, 0.3, -1));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeRetries() {
        new RetryPolicy(-1, 200, 2.0, 4000, 0.3, true, 30000);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsJitterOfOne() {
        new RetryPolicy(2, 200, 2.0, 4000, 1.0, true, 30000);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsShrinkingMultiplier() {
        new RetryPolicy(2, 200, 0.5, 4000, 0.3, true, 30000);
    }
}
