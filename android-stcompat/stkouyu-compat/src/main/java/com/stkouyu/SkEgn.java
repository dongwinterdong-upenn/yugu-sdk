// SPDX-License-Identifier: Apache-2.0
package com.stkouyu;

import com.shengzhiai.yugu.stcompat.internal.SkEgnEmulator;

/**
 * The skegn C API of the Shengtong SDK, emulated in Java on the Yugu cloud platform: no native
 * library is loaded. Engines are handle ids; {@link #skegn_start} takes the Shengtong parameter
 * JSON, {@link #skegn_feed} appends audio, {@link #skegn_stop} evaluates over HTTPS and calls
 * {@code callback.run(id, SKEGN_MESSAGE_TYPE_JSON, json, size)} on the main thread with the same
 * result or error JSON as {@code SkEgnManager}. Return values: 0 success, -1 failure with the error
 * number of skegn_errno.h in {@link #skegn_get_last_error()}.
 */
public final class SkEgn {

    /** Callback of the engine. */
    public interface skegn_callback {
        /**
         * @param id tokenId bytes (ASCII)
         * @param type {@link #SKEGN_MESSAGE_TYPE_JSON}
         * @param data UTF-8 JSON
         * @param size number of bytes in data
         * @return ignored
         */
        int run(byte[] id, int type, byte[] data, int size);
    }

    /** Callback message type: JSON. */
    public static int SKEGN_MESSAGE_TYPE_JSON = 1;
    /** Callback message type: binary. */
    public static int SKEGN_MESSAGE_TYPE_BIN = 2;

    public static int SKEGN_OPT_GET_VERSION = 1;
    public static int SKEGN_OPT_GET_MODULES = 2;
    public static int SKEGN_OPT_GET_TRAFFIC = 3;
    public static int SKEGN_OPT_SET_WIFI_STATUS = 4;
    public static int SKEGN_OPT_GET_PROVISION = 5;
    public static int SKEGN_OPT_GET_SERIAL_NUMBER = 6;

    /**
     * Creates an engine.
     *
     * @param cfg {@code {"appKey":"...","secretKey":"...","cloud":{"server":"...","connectTimeout":10,"serverTimeout":120}}}
     * @return engine handle, 0 on failure
     */
    public static long skegn_new(String cfg, Object androidContext) {
        return SkEgnEmulator.create(cfg, androidContext);
    }

    public static int skegn_delete(long engine) {
        return SkEgnEmulator.delete(engine);
    }

    /**
     * Starts an evaluation; the tokenId is written into {@code id} as ASCII.
     *
     * @param param {@code {"coreProvideType":"cloud","app":{"userId":"..."},"audio":{"audioType":"wav","sampleRate":16000,"channel":1,"sampleBytes":2},"request":{"coreType":"sent.eval","refText":"..."}}}
     */
    public static int skegn_start(long engine, String param, byte[] id, skegn_callback callback, Object context) {
        return SkEgnEmulator.start(engine, param, id, callback, context);
    }

    public static int skegn_feed(long engine, byte[] data, int size) {
        return SkEgnEmulator.feed(engine, data, size);
    }

    public static int skegn_stop(long engine) {
        return SkEgnEmulator.stop(engine);
    }

    public static int skegn_cancel(long engine) {
        return SkEgnEmulator.cancel(engine);
    }

    /** Engine options; the answer is written into {@code data}, the return value is its length. */
    public static int skegn_opt(long engine, int opt, byte[] data, int size) {
        return SkEgnEmulator.opt(engine, opt, data, size);
    }

    public static int skegn_get_device_id(byte[] deviceId, Object androidContext) {
        return SkEgnEmulator.deviceId(deviceId, androidContext);
    }

    public static int skegn_get_last_error() {
        return SkEgnEmulator.lastError();
    }

    /** No provision file is needed in cloud mode; returns 0. */
    public static int skegn_update_provision(String provisionPath, String appKey, String secretKey) {
        return 0;
    }

    /** Answers {@code {"provision":"cloud","message":"cloud mode, no provision file needed"}} through the callback. */
    public static int skegn_inquire_provision(String provisionPath, skegn_callback callback, Object context) {
        return SkEgnEmulator.inquireProvision(provisionPath, callback, context);
    }
}
