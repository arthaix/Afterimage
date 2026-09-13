package ru.arthaix.keystone.ltfix;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Lag metrics written to ltfix-metrics.log in the game (or server) folder, for measuring LittleTiles work objectively.
 * Client, every 5 s: frames, frames over 50 / 250 / 1000 ms, worst frame, tile tags still deferred by packetbudget,
 * LittleTiles rendering jobs queued, rendering retries. Server, every 10 s: ticks over 50 / 250 / 1000 ms, worst tick.
 * Only Forge events and reflection on public fields of other mods: no Minecraft member names.
 */
public final class LtFixMetrics {
    private static final long MS = 1_000_000L;
    private final File file;
    private final SimpleDateFormat stamp = new SimpleDateFormat("HH:mm:ss");

    private long lastFrame;
    private long frameWindow;
    private int frames;
    private int frames50;
    private int frames250;
    private int frames1000;
    private long worstFrame;

    private long tickStart;
    private long tickWindow;
    private int ticks;
    private int ticks50;
    private int ticks250;
    private int ticks1000;
    private long worstTick;

    private Method deferredPending;
    private Field ltThreads;
    private Field ltQueue;
    private Method builderStats;
    private boolean reflected;

    public LtFixMetrics(File gameDir) {
        this.file = new File(gameDir, "ltfix-metrics.log");
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        FrameWatch.frameStarted();
        RegionPrewarm.tick();
        long now = System.nanoTime();
        if (this.lastFrame != 0L) {
            long dt = now - this.lastFrame;
            this.frames++;
            if (dt > 50 * MS) {
                this.frames50++;
            }
            if (dt > 250 * MS) {
                this.frames250++;
            }
            if (dt > 1000 * MS) {
                this.frames1000++;
            }
            if (dt > this.worstFrame) {
                this.worstFrame = dt;
            }
        } else {
            this.frameWindow = now;
        }
        this.lastFrame = now;
        if (now - this.frameWindow >= 5000 * MS) {
            double seconds = (now - this.frameWindow) / 1e9;
            write("client fps " + Math.round(this.frames / seconds) + " frames>50ms " + this.frames50 + " >250ms " + this.frames250
                + " >1s " + this.frames1000 + " worst " + this.worstFrame / MS + "ms deferredTileChunks " + deferred()
                + " ltRenderQueue " + ltQueued() + " ltRetries " + RenderRetry.retries() + " ltDropped " + RenderJobs.dropped()
                + " directMB " + directMb() + " " + builders() + " " + GeometryPacker.stats() + " " + TileBuilders.stats() + " " + LtPreparse.stats() + " " + RegionPrewarm.stats() + " "
                + LtRenderScan.last());
            LtRenderScan.request();
            this.frameWindow = now;
            this.frames = 0;
            this.frames50 = 0;
            this.frames250 = 0;
            this.frames1000 = 0;
            this.worstFrame = 0L;
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        long now = System.nanoTime();
        if (event.phase == TickEvent.Phase.START) {
            FreezeWatch.tickStarted();
            FrameWatch.tickStarted();
            this.tickStart = now;
            if (this.tickWindow == 0L) {
                this.tickWindow = now;
            }
            return;
        }
        FreezeWatch.tickEnded();
        FrameWatch.tickEnded();
        if (this.tickStart == 0L) {
            return;
        }
        long dt = now - this.tickStart;
        this.ticks++;
        if (dt > 50 * MS) {
            this.ticks50++;
        }
        if (dt > 250 * MS) {
            this.ticks250++;
        }
        if (dt > 1000 * MS) {
            this.ticks1000++;
        }
        if (dt > this.worstTick) {
            this.worstTick = dt;
        }
        if (now - this.tickWindow >= 10_000 * MS) {
            write("server ticks " + this.ticks + " >50ms " + this.ticks50 + " >250ms " + this.ticks250 + " >1s " + this.ticks1000
                + " worst " + this.worstTick / MS + "ms " + tagCache() + "; " + editStats() + ", " + NeighborDedup.stats());
            this.tickWindow = now;
            this.ticks = 0;
            this.ticks50 = 0;
            this.ticks250 = 0;
            this.ticks1000 = 0;
            this.worstTick = 0L;
        }
    }

    private void reflect() {
        if (this.reflected) {
            return;
        }
        this.reflected = true;
        try {
            this.deferredPending = Class.forName("ru.arthaix.keystone.packetbudget.DeferredTiles").getMethod("pending");
        } catch (Throwable ignored) {
        }
        try {
            this.builderStats = Class.forName("ru.arthaix.keystone.teunloadbatch.RenderBuilders").getMethod("stats");
        } catch (Throwable ignored) {
        }
        try {
            Class<?> rt = Class.forName("com.creativemd.littletiles.client.render.cache.RenderingThread");
            this.ltThreads = rt.getField("threads");
            this.ltQueue = rt.getField("updateCoords");
        } catch (Throwable ignored) {
        }
    }

    private String deferred() {
        reflect();
        try {
            return this.deferredPending == null ? "n/a" : String.valueOf(this.deferredPending.invoke(null));
        } catch (Throwable t) {
            return "err";
        }
    }

    private String ltQueued() {
        reflect();
        if (this.ltThreads == null) {
            return "n/a";
        }
        try {
            Object list = this.ltThreads.get(null);
            if (!(list instanceof List)) {
                return "0";
            }
            int sum = 0;
            for (Object thread : (List<?>) list) {
                Object q = this.ltQueue.get(thread);
                if (q instanceof Collection) {
                    sum += ((Collection<?>) q).size();
                }
            }
            return String.valueOf(sum);
        } catch (Throwable t) {
            return "err";
        }
    }

    private String builders() {
        reflect();
        try {
            return this.builderStats == null ? "builders n/a" : String.valueOf(this.builderStats.invoke(null));
        } catch (Throwable t) {
            return "builders err";
        }
    }

    private static java.lang.reflect.Method tagCacheStats;
    private static boolean tagCacheLooked;

    private static String tagCache() {
        if (!tagCacheLooked) {
            tagCacheLooked = true;
            try {
                tagCacheStats = Class.forName("ru.arthaix.keystone.teunloadbatch.TagCache").getMethod("stats");
            } catch (Throwable ignored) {
            }
        }
        try {
            return tagCacheStats == null ? "tagcache n/a" : String.valueOf(tagCacheStats.invoke(null));
        } catch (Throwable t) {
            return "tagcache err";
        }
    }

    private static java.lang.reflect.Method editStatsMethod;
    private static boolean editStatsLooked;

    private static String editStats() {
        if (!editStatsLooked) {
            editStatsLooked = true;
            try {
                editStatsMethod = Class.forName("ru.arthaix.keystone.teunloadbatch.EditStats").getMethod("stats");
            } catch (Throwable ignored) {
            }
        }
        try {
            return editStatsMethod == null ? "edits n/a" : String.valueOf(editStatsMethod.invoke(null));
        } catch (Throwable t) {
            return "edits err";
        }
    }

    private static long directMb() {
        for (java.lang.management.BufferPoolMXBean pool : java.lang.management.ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class)) {
            if ("direct".equals(pool.getName())) {
                return pool.getMemoryUsed() >> 20;
            }
        }
        return -1L;
    }

    private synchronized void write(String line) {
        try (PrintWriter w = new PrintWriter(new FileWriter(this.file, true))) {
            w.println(this.stamp.format(new Date()) + " " + line);
        } catch (Throwable ignored) {
        }
    }
}
