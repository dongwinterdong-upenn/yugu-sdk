package com.shengzhiai.yugu;

import com.shengzhiai.yugu.internal.WsTransport;

import java.util.Random;

/** Access to the package private test hooks of the builders. */
public final class TestHooks {
    private TestHooks() {
    }

    public static YuguClient.Builder transport(YuguClient.Builder b, WsTransport t) {
        return b.transport(t);
    }

    public static YuguClient.Builder random(YuguClient.Builder b, Random r) {
        return b.random(r);
    }
}
