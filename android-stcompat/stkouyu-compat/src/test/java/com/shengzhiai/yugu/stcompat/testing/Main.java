package com.shengzhiai.yugu.stcompat.testing;

import android.os.Looper;

import java.time.Duration;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

/** Main looper helpers for Robolectric (PAUSED looper mode). */
public final class Main {
    private Main() {
    }

    /** Runs everything posted to the main looper so far. */
    public static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    /** Advances the main looper clock, running delayed tasks. */
    public static void idleFor(long millis) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    /** Idles the main looper until the condition holds (background threads keep running). */
    public static void await(String what, long timeoutMs, BooleanSupplier condition) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (true) {
            idle();
            if (condition.getAsBoolean()) {
                return;
            }
            if (System.currentTimeMillis() > end) {
                fail("timed out after " + timeoutMs + " ms waiting for " + what);
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted");
            }
        }
    }

    /** Idles for a short real time to let background threads finish, then runs main tasks. */
    public static void settle(long millis) {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            idle();
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        idle();
    }

    public static boolean onMain() {
        return Looper.myLooper() == Looper.getMainLooper();
    }
}
