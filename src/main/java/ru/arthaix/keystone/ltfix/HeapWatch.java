package ru.arthaix.keystone.ltfix;

import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.management.ObjectName;

/**
 * "OutOfMemoryError: Java heap space" came without a trace of what filled the heap. When the heap is still more than
 * -Dltfix.heapHistogramPercent (85) full right after a collection, the biggest classes on the heap (garbage included,
 * so no extra full collection is forced) are appended to ltfix-heap.log, at most every -Dltfix.heapHistogramMinutes
 * (10). Walking the heap stops the game for a moment; it only happens when the heap is close to running out anyway.
 */
public final class HeapWatch {
    private static final double THRESHOLD = Integer.getInteger("ltfix.heapHistogramPercent", 85) / 100.0;
    private static final long INTERVAL_NANOS = Long.getLong("ltfix.heapHistogramMinutes", 10L) * 60_000_000_000L;
    private static final int TOP = 40;
    private static volatile long last = Long.MIN_VALUE / 2;
    private static volatile boolean running;

    private HeapWatch() {
    }

    /** GC notification thread: heap in use after a collection, as a fraction of the maximum. */
    static void afterGc(double used) {
        long now = System.nanoTime();
        if (used < THRESHOLD || running || now - last < INTERVAL_NANOS) {
            return;
        }
        running = true;
        last = now;
        Thread t = new Thread(() -> {
            try {
                write(used);
            } catch (Throwable ignored) {
                // diagnostics only
            } finally {
                running = false;
            }
        }, "ltfix heap histogram");
        t.setDaemon(true);
        t.start();
    }

    private static void write(double used) throws Exception {
        String histogram = (String) ManagementFactory.getPlatformMBeanServer().invoke(
            new ObjectName("com.sun.management:type=DiagnosticCommand"), "gcClassHistogram",
            new Object[] {new String[] {"-all"}}, new String[] {String[].class.getName()});
        String[] lines = histogram.split("\n");
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream("ltfix-heap.log", true), StandardCharsets.UTF_8))) {
            out.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " heap after GC " + Math.round(used * 100)
                + "% of " + (Runtime.getRuntime().maxMemory() >> 20) + " MB; " + GeometryPacker.stats());
            for (int i = 0; i < lines.length && i < TOP + 3; i++) {
                out.println(lines[i]);
            }
            if (lines.length > 0) {
                out.println(lines[lines.length - 1]);
            }
            out.println();
        }
    }
}
