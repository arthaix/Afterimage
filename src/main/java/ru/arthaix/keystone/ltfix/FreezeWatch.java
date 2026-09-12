package ru.arthaix.keystone.ltfix;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

/**
 * Server freeze catcher. A daemon thread watches the tick start time published by LtFixMetrics. When a tick has been
 * running for longer than THRESHOLD_MS it writes the stack of the thread that runs the tick to ltfix-freeze.log, then
 * again every REPEAT_MS while the tick is still stuck, so any multi-second freeze explains itself afterwards.
 */
public final class FreezeWatch implements Runnable {
    private static final long THRESHOLD_MS = Long.getLong("ltfix.freezeMs", 5000L);
    private static final long REPEAT_MS = 2000L;
    private static volatile long tickStartMillis;
    private static volatile Thread tickThread;

    private final File file;
    private final SimpleDateFormat stamp = new SimpleDateFormat("HH:mm:ss");

    private FreezeWatch(File file) {
        this.file = file;
    }

    public static void start(File gameDir) {
        Thread t = new Thread(new FreezeWatch(new File(gameDir, "ltfix-freeze.log")), "ltfix freeze watch");
        t.setDaemon(true);
        t.start();
    }

    /** Server tick START. */
    public static void tickStarted() {
        tickThread = Thread.currentThread();
        tickStartMillis = System.currentTimeMillis();
    }

    /** Server tick END. */
    public static void tickEnded() {
        tickStartMillis = 0L;
    }

    @Override
    public void run() {
        long reportedFor = 0L;
        long lastReport = 0L;
        while (true) {
            try {
                Thread.sleep(500L);
                long start = tickStartMillis;
                Thread th = tickThread;
                if (start == 0L || th == null) {
                    reportedFor = 0L;
                    continue;
                }
                long now = System.currentTimeMillis();
                long age = now - start;
                if (age < THRESHOLD_MS) {
                    continue;
                }
                if (reportedFor == start && now - lastReport < REPEAT_MS) {
                    continue;
                }
                reportedFor = start;
                lastReport = now;
                StringBuilder sb = new StringBuilder();
                sb.append(this.stamp.format(new Date())).append(" tick running for ").append(age).append(" ms, thread '")
                    .append(th.getName()).append("'\n");
                for (StackTraceElement e : th.getStackTrace()) {
                    sb.append("    at ").append(e).append('\n');
                }
                try (PrintWriter w = new PrintWriter(new FileWriter(this.file, true))) {
                    w.print(sb);
                }
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable ignored) {
                // never let the watcher die
            }
        }
    }

    @SuppressWarnings("unused")
    private static Map<Thread, StackTraceElement[]> all() {
        return Thread.getAllStackTraces();
    }
}
