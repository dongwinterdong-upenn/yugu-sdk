package com.shengzhiai.yugu;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Backoff math of DESIGN 2.3 and 2.5 with a deterministic random source. */
class RetryPolicyTest {

    @Test
    void defaultsMatchDesign() {
        RetryPolicy p = RetryPolicy.defaults();
        assertEquals(2, p.getMaxRetries());
        assertEquals(200, p.getInitialDelayMs());
        assertEquals(2.0, p.getMultiplier());
        assertEquals(4000, p.getMaxDelayMs());
        assertEquals(0.3, p.getJitter());
        assertTrue(p.isRespectRetryAfter());
        assertEquals(30000, p.getMaxRetryAfterMs());
        assertEquals(0, RetryPolicy.none().getMaxRetries());
        assertSame(p, RetryPolicy.defaults());
        assertTrue(p.toString().contains("maxRetries=2"));
    }

    @Test
    void baseDelaysAre200And400ThenCapped() {
        RetryPolicy p = RetryPolicy.defaults();
        assertEquals(200, p.baseDelayMs(1));
        assertEquals(400, p.baseDelayMs(2));
        assertEquals(800, p.baseDelayMs(3));
        assertEquals(3200, p.baseDelayMs(5));
        assertEquals(4000, p.baseDelayMs(6));
        assertEquals(4000, p.baseDelayMs(30));
    }

    @Test
    void jitterFormulaIsExact() {
        RetryPolicy p = RetryPolicy.defaults();
        assertEquals(200, p.delayMs(1, 0.5, -1));
        assertEquals(140, p.delayMs(1, 0.0, -1));
        assertEquals(260, p.delayMs(1, 1.0, -1));
        assertEquals(280, p.delayMs(2, 0.0, -1));
        assertEquals(520, p.delayMs(2, 1.0, -1));
        assertEquals(5200, p.delayMs(9, 1.0, -1), "jitter applies after the cap");
        assertEquals(0, RetryPolicy.builder().initialDelayMs(0).build().delayMs(1, 0.7, -1));
    }

    @Test
    void seededRandomIsDeterministicAndWithinBounds() {
        RetryPolicy p = RetryPolicy.defaults();
        Random a = new Random(42);
        Random b = new Random(42);
        Random expected = new Random(42);
        for (int n = 1; n <= 8; n++) {
            long d = p.delayMs(n, a, -1);
            assertEquals(d, p.delayMs(n, b, -1));
            double u = (expected.nextDouble() * 2 - 1) * 0.3;
            assertEquals(Math.round(p.baseDelayMs(n) * (1 + u)), d);
            assertTrue(d >= Math.round(p.baseDelayMs(n) * 0.7) && d <= Math.round(p.baseDelayMs(n) * 1.3), "n=" + n + " d=" + d);
        }
    }

    @Test
    void retryAfterIsHonouredAndCapped() {
        RetryPolicy p = RetryPolicy.defaults();
        assertEquals(1000, p.delayMs(1, 0.5, 1000));
        assertEquals(200, p.delayMs(1, 0.5, 50), "never shorter than the backoff");
        assertEquals(30000, p.delayMs(1, 0.5, 90000), "capped at maxRetryAfterMs");
        RetryPolicy ignore = p.toBuilder().respectRetryAfter(false).build();
        assertEquals(200, ignore.delayMs(1, 0.5, 5000));
        assertFalse(ignore.isRespectRetryAfter());
    }

    @Test
    void builderValidation() {
        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.builder().maxRetries(-1).build());
        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.builder().initialDelayMs(-1).build());
        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.builder().multiplier(0.5).build());
        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.builder().jitter(1.5).build());
        RetryPolicy p = RetryPolicy.builder().maxRetries(5).initialDelayMs(10).multiplier(3).maxDelayMs(100)
                .jitter(0).maxRetryAfterMs(50).build();
        assertEquals(10, p.delayMs(1, 0.9, -1));
        assertEquals(30, p.delayMs(2, 0.1, -1));
        assertEquals(100, p.delayMs(4, 0.1, -1));
        assertEquals(50, p.delayMs(1, 0.1, 999));
        assertEquals(p.toString(), p.toBuilder().build().toString());
    }

    @Test
    void reconnectPolicyMath() {
        ReconnectPolicy r = ReconnectPolicy.defaults();
        assertTrue(r.isEnabled());
        assertEquals(8, r.getMaxAttempts());
        assertEquals(500, r.getInitialDelayMs());
        assertEquals(2.0, r.getMultiplier());
        assertEquals(4000, r.getMaxDelayMs());
        assertEquals(0.3, r.getJitter());
        assertEquals(500, r.baseDelayMs(1));
        assertEquals(1000, r.baseDelayMs(2));
        assertEquals(2000, r.baseDelayMs(3));
        assertEquals(4000, r.baseDelayMs(4));
        long budget = 0;
        for (int n = 1; n <= r.getMaxAttempts(); n++) {
            budget += r.baseDelayMs(n);
        }
        assertEquals(23_500, budget, "0.5 + 1 + 2 + 4 * 5 s: the defaults outlast a 10 s network drop");
        assertEquals(350, r.delayMs(1, 0.0));
        assertEquals(650, r.delayMs(1, 1.0));
        Random seeded = new Random(7);
        long d = r.delayMs(2, seeded);
        assertTrue(d >= 700 && d <= 1300);
        assertFalse(ReconnectPolicy.disabled().isEnabled());
        assertEquals(r.toString(), r.toBuilder().build().toString());
        assertThrows(IllegalArgumentException.class, () -> ReconnectPolicy.builder().maxAttempts(-1).build());
        assertThrows(IllegalArgumentException.class, () -> ReconnectPolicy.builder().maxDelayMs(-1).build());
        assertThrows(IllegalArgumentException.class, () -> ReconnectPolicy.builder().multiplier(0).build());
        assertThrows(IllegalArgumentException.class, () -> ReconnectPolicy.builder().jitter(-0.1).build());
    }
}
