package com.shengzhiai.yugu;

import com.shengzhiai.yugu.internal.Auth;
import com.shengzhiai.yugu.internal.Log;
import com.shengzhiai.yugu.internal.WsTransport;

import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

/** Dependencies a client hands to its stream sessions. */
final class StreamContext {
    final ClientOptions options;
    final WsTransport transport;
    final Auth auth;
    final ScheduledExecutorService scheduler;
    final Executor callbackPool;
    final Log log;
    final EventListener events;
    final Random random;
    final Consumer<StreamSession> onFinished;

    StreamContext(ClientOptions options, WsTransport transport, Auth auth, ScheduledExecutorService scheduler,
                  Executor callbackPool, Log log, EventListener events, Random random, Consumer<StreamSession> onFinished) {
        this.options = options;
        this.transport = transport;
        this.auth = auth;
        this.scheduler = scheduler;
        this.callbackPool = callbackPool;
        this.log = log;
        this.events = events;
        this.random = random;
        this.onFinished = onFinished;
    }
}
