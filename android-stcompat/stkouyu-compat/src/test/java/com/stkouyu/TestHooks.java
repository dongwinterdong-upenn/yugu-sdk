package com.stkouyu;

/** Test access to package-private hooks of com.stkouyu. */
public final class TestHooks {
    private TestHooks() {
    }

    public static void resetManager() {
        SkEgnManager.resetForTests();
    }
}
