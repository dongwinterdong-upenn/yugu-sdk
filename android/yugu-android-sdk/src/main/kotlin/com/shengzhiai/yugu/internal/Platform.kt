package com.shengzhiai.yugu.internal

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide resources shared by every client, so creating and closing clients repeatedly does
 * not accumulate threads or connection pools. Idle threads time out.
 */
internal object Shared {

    private fun factory(prefix: String): ThreadFactory {
        val n = AtomicInteger()
        return ThreadFactory { r ->
            Thread(r, prefix + "-" + n.incrementAndGet()).apply { isDaemon = true }
        }
    }

    /** Timers: reconnect delays, handshake and result timeouts. */
    val scheduler: ScheduledThreadPoolExecutor by lazy {
        ScheduledThreadPoolExecutor(1, factory("yugu-scheduler")).apply {
            removeOnCancelPolicy = true
            setKeepAliveTime(15, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
        }
    }

    /** Fallback callback thread when there is no Android main looper (plain JVM). */
    val callbackExecutor: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(1, 1, 15, TimeUnit.SECONDS, LinkedBlockingQueue(), factory("yugu-callback")).apply {
            allowCoreThreadTimeOut(true)
        }
    }

    /** Worker threads of the async API. */
    val asyncExecutor: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(0, Int.MAX_VALUE, 30, TimeUnit.SECONDS, SynchronousQueue(), factory("yugu-async"))
    }

    /** Base OkHttp client; each YuguClient derives its own settings with newBuilder(). */
    val okHttp: OkHttpClient by lazy { OkHttpClient() }
}

/** Android specifics, guarded so that plain JVM code paths never touch a stub. */
internal object Platform {

    /** Main thread executor on Android, null on a plain JVM. */
    fun mainExecutorOrNull(): Executor? = try {
        if (Looper.getMainLooper() != null) MainThreadExecutor else null
    } catch (t: Throwable) {
        null
    }

    fun isMainThread(): Boolean = try {
        val main = Looper.getMainLooper()
        main != null && Looper.myLooper() == main
    } catch (t: Throwable) {
        false
    }

    fun defaultCallbackExecutor(): Executor = mainExecutorOrNull() ?: Shared.callbackExecutor
}

/** Posts to the Android main looper. */
internal object MainThreadExecutor : Executor {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override fun execute(command: Runnable) {
        if (!handler.post(command)) throw RejectedExecutionException("main looper is quitting")
    }
}

/**
 * Runs tasks one at a time, in submission order, on [target]. Callbacks of one session or one
 * recorder never run concurrently. When [target] rejects a task the shared fallback runs it.
 */
internal class SerialExecutor(private val target: Executor) : Executor {
    private val tasks = ArrayDeque<Runnable>()
    private var active: Runnable? = null

    override fun execute(command: Runnable) {
        synchronized(this) {
            tasks.add(Runnable {
                try {
                    command.run()
                } finally {
                    scheduleNext()
                }
            })
            if (active == null) scheduleNext()
        }
    }

    private fun scheduleNext() {
        synchronized(this) {
            val next = tasks.poll()
            active = next
            if (next != null) {
                try {
                    target.execute(next)
                } catch (e: RejectedExecutionException) {
                    Shared.callbackExecutor.execute(next)
                }
            }
        }
    }
}
