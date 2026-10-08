// SPDX-License-Identifier: Apache-2.0
package com.stkouyu.util;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;

import java.util.ArrayList;
import java.util.List;

/** Handler factory of the Shengtong SDK: main-thread handlers, handlers on new worker threads. */
public class HandlerUtils {

    /** Receives the messages of a handler created with a callback. */
    public interface HandlerDispose {
        void handleMessage(Message msg);
    }

    private static volatile HandlerUtils instance;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final List<HandlerThread> children = new ArrayList<HandlerThread>();

    private HandlerUtils() {
    }

    public static HandlerUtils getInstance() {
        HandlerUtils h = instance;
        if (h == null) {
            synchronized (HandlerUtils.class) {
                h = instance;
                if (h == null) {
                    h = new HandlerUtils();
                    instance = h;
                }
            }
        }
        return h;
    }

    private static Looper currentOrMain() {
        Looper l = Looper.myLooper();
        return l != null ? l : Looper.getMainLooper();
    }

    /** Handler on the calling thread's looper (main looper when the thread has none). */
    public Handler getNewHandler() {
        return new Handler(currentOrMain());
    }

    public Handler getNewHandlerCB(final HandlerDispose dispose) {
        return new Handler(currentOrMain()) {
            @Override
            public void handleMessage(Message msg) {
                if (dispose != null) {
                    dispose.handleMessage(msg);
                }
            }
        };
    }

    /** Handler on a new worker thread; {@link #UIOnFinish()} stops the worker threads. */
    public Handler getNewChildHandler() {
        return new Handler(newChildLooper());
    }

    public Handler getNewChildHandlerCB(final HandlerDispose dispose) {
        return new Handler(newChildLooper()) {
            @Override
            public void handleMessage(Message msg) {
                if (dispose != null) {
                    dispose.handleMessage(msg);
                }
            }
        };
    }

    private Looper newChildLooper() {
        HandlerThread t = new HandlerThread("stkouyu-child");
        t.start();
        synchronized (children) {
            children.add(t);
        }
        return t.getLooper();
    }

    /** Shared main-thread handler. */
    public Handler getUIHandler() {
        return uiHandler;
    }

    public Handler getUIHandlerCB(final HandlerDispose dispose) {
        return new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                if (dispose != null) {
                    dispose.handleMessage(msg);
                }
            }
        };
    }

    /** Removes pending work of the shared main-thread handler and stops the worker threads. */
    public void UIOnFinish() {
        uiHandler.removeCallbacksAndMessages(null);
        List<HandlerThread> copy;
        synchronized (children) {
            copy = new ArrayList<HandlerThread>(children);
            children.clear();
        }
        for (HandlerThread t : copy) {
            t.quit();
        }
    }
}
