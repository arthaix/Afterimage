package ru.arthaix.keystone.ltfix;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stall sampler. The watched thread marks the start (and, for the server, the end) of every frame or tick. A daemon
 * thread samples its stack every SAMPLE_MS while the current one is older than START_MS and has not ended; when it took
 * the threshold or longer, its most frequent stacks are logged with the garbage collection time spent inside it.
 * Client: frames of 50 ms or more in ltfix-hitches.log. Dedicated server: ticks of 150 ms or more in ltfix-ticks.log.
 * Frames and ticks under START_MS cost nothing.
 */
public final class FrameWatch implements Runnable {
    private static final long MS = 1_000_000L;
    private static final long START_MS = 25L;
    /** Every sample is a thread-dump safepoint that stops all threads (3 ms gave ~55 per second in long server ticks). */
    private static final long SAMPLE_MS = Long.getLong("ltfix.sampleMs", 10L);
    private static final int DEPTH = 28;
    private static final int MAX_REPORTS_PER_MINUTE = 40;

    private static volatile FrameWatch client;
    private static volatile FrameWatch server;

    private final File file;
    private final String kind;
    private final long thresholdMs;

    // written by the watched thread
    private volatile Thread thread;
    private volatile long start;
    private volatile long seq;
    private volatile long end;
    private volatile long endSeq = -1L;

    // watcher thread only
    private final SimpleDateFormat stamp = new SimpleDateFormat("HH:mm:ss");
    private final Map<String, Integer> stacks = new HashMap<String, Integer>();
    private long sampledSeq = -1L;
    private long sampledStart;
    private long sampledEnd;
    private long sampledGc;
    private int samples;
    private long minuteStart;
    private int reportsThisMinute;

    private FrameWatch(File file, String kind, long thresholdMs) {
        this.file = file;
        this.kind = kind;
        this.thresholdMs = thresholdMs;
    }

    public static void startClient(File gameDir) {
        client = start(new File(gameDir, "ltfix-hitches.log"), "hitch", Long.getLong("ltfix.hitchMs", 50L));
    }

    public static void startServer(File gameDir) {
        server = start(new File(gameDir, "ltfix-ticks.log"), "tick", Long.getLong("ltfix.tickMs", 150L));
    }

    private static FrameWatch start(File file, String kind, long thresholdMs) {
        FrameWatch w = new FrameWatch(LogRotate.prepare(file, kind), kind, thresholdMs);
        Thread t = new Thread(w, "ltfix " + kind + " watch");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
        return w;
    }

    private static long clientFrames;

    /** Client thread, RenderTickEvent START. */
    public static void frameStarted() {
        clientFrames++;
        FrameWatch w = client;
        if (w != null) {
            w.mark();
        }
    }

    /** Client thread: number of frames started so far. */
    public static long clientFrame() {
        return clientFrames;
    }

    /** Server thread, ServerTickEvent START. */
    public static void tickStarted() {
        FrameWatch w = server;
        if (w != null) {
            w.mark();
        }
    }

    /** Server thread, ServerTickEvent END. */
    public static void tickEnded() {
        FrameWatch w = server;
        if (w != null) {
            w.end = System.nanoTime();
            w.endSeq = w.seq;
        }
    }

    private void mark() {
        this.thread = Thread.currentThread();
        this.start = System.nanoTime();
        this.seq++;
    }

    @Override
    public void run() {
        while (true) {
            try {
                Thread.sleep(SAMPLE_MS);
                long s = this.seq;
                long st = this.start;
                long es = this.endSeq;
                long e = this.end;
                Thread th = this.thread;
                if (th == null) {
                    continue;
                }
                if (es == this.sampledSeq && e > this.sampledStart) {
                    this.sampledEnd = e;
                }
                long now = System.nanoTime();
                if (s != this.sampledSeq) {
                    // a new frame or tick began: the sampled one ended at its recorded end, or at the new start
                    if (this.samples > 0) {
                        long endAt = this.sampledEnd > this.sampledStart ? this.sampledEnd : st;
                        long duration = (endAt - this.sampledStart) / MS;
                        if (duration >= this.thresholdMs) {
                            report(duration, GcTime.totalMs() - this.sampledGc);
                        }
                    }
                    this.stacks.clear();
                    this.samples = 0;
                    this.sampledSeq = s;
                    this.sampledStart = st;
                    this.sampledEnd = 0L;
                    this.sampledGc = GcTime.totalMs();
                }
                if (now - st < START_MS * MS || (es == s && e > st)) {
                    continue;
                }
                StackTraceElement[] trace = th.getStackTrace();
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < trace.length && i < DEPTH; i++) {
                    sb.append("      at ").append(trace[i]).append('\n');
                }
                String key = sb.toString();
                Integer c = this.stacks.get(key);
                this.stacks.put(key, c == null ? 1 : c + 1);
                this.samples++;
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable ignored) {
                // never let the watcher die
            }
        }
    }

    private void report(long durationMs, long gcMs) {
        long nowMs = System.currentTimeMillis();
        if (nowMs - this.minuteStart > 60_000L) {
            this.minuteStart = nowMs;
            this.reportsThisMinute = 0;
        }
        if (++this.reportsThisMinute > MAX_REPORTS_PER_MINUTE) {
            return;
        }
        List<Map.Entry<String, Integer>> list = new ArrayList<Map.Entry<String, Integer>>(this.stacks.entrySet());
        Collections.sort(list, (a, b) -> b.getValue() - a.getValue());
        StringBuilder out = new StringBuilder();
        out.append(this.stamp.format(new Date())).append(' ').append(this.kind).append(' ').append(durationMs).append(" ms, gc ")
            .append(gcMs).append(" ms, ").append(this.samples).append(" samples after the first ").append(START_MS).append(" ms\n");
        if ("hitch".equals(this.kind) && durationMs >= 1000L) {
            // a stall inside the driver says only that the GPU was busy: what was uploaded, and how full VRAM is
            try {
                out.append("   ").append(GpuTrace.describe()).append('\n');
            } catch (Throwable ignored) {
            }
        }
        for (int i = 0; i < list.size() && i < 3; i++) {
            out.append("   ").append(list.get(i).getValue()).append(" samples:\n").append(list.get(i).getKey());
        }
        try (PrintWriter w = new PrintWriter(new FileWriter(this.file, true))) {
            w.print(out);
        } catch (Throwable ignored) {
        }
    }
}
