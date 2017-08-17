package com.epicgames.ue4;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ThreadManager {
    private static final ExecutorService cachedThreadPool = Executors.newCachedThreadPool();
    private static final ScheduledExecutorService scheduledExecutorService = new ScheduledThreadPoolExecutor(5);

    private ThreadManager() {
        throw new UnsupportedOperationException();
    }

    public static void execute(final Runnable runnable) {
        cachedThreadPool.execute(runnable);
    }

    public static void schedule(final Runnable runnable, final long time, final TimeUnit timeUnit) {
        scheduledExecutorService.schedule(runnable, time, timeUnit);
    }

    public static void scheduleAtFixedRate(final Runnable runnable, final long time, final TimeUnit timeUnit) {
        scheduledExecutorService.scheduleAtFixedRate(runnable, 0, time, timeUnit);
    }

    public static Future<?> submit(final Callable<?> callable) {
        return cachedThreadPool.submit(callable);
    }
}
