package ru.arthaix.keystone.ltfix;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Delayed, silent retry for LittleTiles rendering jobs whose tile entity has no data yet. */
public final class RenderRetry {
    private static final long DELAY_MS = Long.getLong("ltfix.retryMs", 200L);
    private static final String NOT_LOADED = "Tileentity is not loaded yet";
    private static final ThreadLocal<boolean[]> DEFERRED = new ThreadLocal<boolean[]>() {
        @Override
        protected boolean[] initialValue() {
            return new boolean[1];
        }
    };
    private static final ScheduledExecutorService EXEC = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "LittleTiles render retry");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicLong RETRIES = new AtomicLong();
    private static volatile long lastReport;
    /** consecutive retries per job: the delay doubles each time, up to MAX_DELAY_MS */
    private static final long MAX_DELAY_MS = Long.getLong("ltfix.retryMaxMs", 5000L);
    private static final java.util.Map<Object, Integer> ATTEMPTS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Object, Integer>());

    private RenderRetry() {
    }

    public static long retries() {
        return RETRIES.get();
    }

    /** Called instead of printStackTrace in the rendering thread's outer exception handler. */
    public static void onException(Exception e) {
        if (NOT_LOADED.equals(e.getMessage())) {
            DEFERRED.get()[0] = true;
            long n = RETRIES.incrementAndGet();
            long now = System.currentTimeMillis();
            if (now - lastReport > 60_000L) {
                lastReport = now;
                System.out.println("[ltfix] LittleTiles rendering: " + n + " tiles without data so far, retried after " + DELAY_MS + " ms instead of spinning");
            }
            return;
        }
        e.printStackTrace();
    }

    /** Called instead of queue.add(data) in the rendering thread. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean requeue(final ConcurrentLinkedQueue queue, final Object data) {
        boolean[] flag = DEFERRED.get();
        if (flag[0]) {
            flag[0] = false;
            if (RenderJobs.dropIfDead(data)) {
                // its chunk unloaded while the job waited: nothing will ever load it again
                return true;
            }
            Integer n = ATTEMPTS.get(data);
            int attempt = n == null ? 0 : n;
            ATTEMPTS.put(data, attempt + 1);
            long delay = Math.min(MAX_DELAY_MS, DELAY_MS << Math.min(attempt, 10));
            EXEC.schedule(() -> queue.add(data), delay, TimeUnit.MILLISECONDS);
            return true;
        }
        return queue.add(data);
    }
}
