// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

import android.os.Handler;
import android.os.Looper;

/** Every listener callback of the compat layer runs on the main thread through this handler. */
public final class MainThread {
    private static volatile Handler handler;

    private MainThread() {
    }

    public static Handler handler() {
        Handler h = handler;
        Looper main = Looper.getMainLooper();
        if (h == null || h.getLooper() != main) {
            h = new Handler(main);
            handler = h;
        }
        return h;
    }

    public static boolean isMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    public static void post(Runnable r) {
        handler().post(r);
    }

    public static void postDelayed(Runnable r, long delayMs) {
        handler().postDelayed(r, delayMs);
    }

    public static void remove(Runnable r) {
        if (r != null) {
            handler().removeCallbacks(r);
        }
    }

    /** Runs now when already on the main thread, otherwise posts. */
    public static void run(Runnable r) {
        if (isMainThread()) {
            r.run();
        } else {
            post(r);
        }
    }
}
