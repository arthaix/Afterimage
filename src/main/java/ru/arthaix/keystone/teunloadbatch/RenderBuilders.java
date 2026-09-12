package ru.arthaix.keystone.teunloadbatch;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RegionRenderCacheBuilder;
import ru.arthaix.keystone.teunloadbatch.mixin.BufferBuilderAccessor;

/**
 * Vanilla keeps chunk workers * 10 RegionRenderCacheBuilders (240 on a 24-thread CPU with a big heap). Each is four
 * direct vertex buffers that grow to fit the largest section they ever built and never shrink; LittleTiles merges its
 * tiles into them, so on a long flight over the city the pool reached ~10 GB of native memory and pushed the machine
 * into the page file. Here: buildersPerWorker (3) builders per worker, and once the pool is over builderBudgetMB (1024)
 * a builder that grew is swapped for a fresh one when a chunk worker takes it (the old one is left to the collector).
 */
public final class RenderBuilders {
    public static final int PER_WORKER = Math.max(1, Integer.getInteger("teunloadbatch.buildersPerWorker", 3));
    private static final long BUDGET = Long.getLong("teunloadbatch.builderBudgetMB", 1024L) << 20;
    /** four buffers of 2097152, 131072, 131072 and 262144 ints: a builder that has not grown */
    private static final long FRESH_BYTES = (2097152L + 131072L + 131072L + 262144L) * 4L;

    private static final Map<RegionRenderCacheBuilder, Long> SIZES = new WeakHashMap<RegionRenderCacheBuilder, Long>();
    private static long total;
    private static long replaced;

    private RenderBuilders() {
    }

    /** ChunkRenderDispatcher.freeRenderBuilder, client thread: remember how big the builder is now. */
    public static void onReturn(RegionRenderCacheBuilder builder) {
        try {
            long bytes = bytes(builder);
            synchronized (SIZES) {
                Long prev = SIZES.put(builder, bytes);
                total += bytes - (prev == null ? 0L : prev);
            }
        } catch (Throwable ignored) {
        }
    }

    /** ChunkRenderDispatcher.allocateRenderBuilder: the builder the caller gets instead (the same one, or a fresh one). */
    public static RegionRenderCacheBuilder onTake(RegionRenderCacheBuilder builder) {
        if (builder == null) {
            return null;
        }
        try {
            if (Minecraft.func_71410_x().func_152345_ab()) {
                // stopWorkerThreads drains the pool on the client thread: no allocations there
                return builder;
            }
            synchronized (SIZES) {
                Long bytes = SIZES.get(builder);
                if (bytes == null || total <= BUDGET || bytes <= FRESH_BYTES * 2) {
                    return builder;
                }
                SIZES.remove(builder);
                total -= bytes;
            }
            RegionRenderCacheBuilder fresh = new RegionRenderCacheBuilder();
            long freshBytes = bytes(fresh);
            synchronized (SIZES) {
                SIZES.put(fresh, freshBytes);
                total += freshBytes;
                replaced++;
            }
            return fresh;
        } catch (Throwable t) {
            return builder;
        }
    }

    private static long bytes(RegionRenderCacheBuilder builder) {
        long sum = 0L;
        for (int i = 0; i < 4; i++) {
            ByteBuffer b = ((BufferBuilderAccessor) builder.func_179039_a(i)).teunloadbatch$byteBuffer();
            if (b != null) {
                sum += b.capacity();
            }
        }
        return sum;
    }

    private static volatile String pool = "pool ?";

    /** ChunkRenderDispatcher constructor RETURN. */
    public static void logPool(int before, int after, int workers, int removed) {
        pool = "pool " + after + "/" + before;
        System.out.println("[teunloadbatch] chunk render builders " + before + " -> " + after + " for " + workers + " worker threads ("
            + removed + " dropped)");
    }

    public static String stats() {
        synchronized (SIZES) {
            return "builders " + SIZES.size() + " " + (total >> 20) + "MB replaced " + replaced + " " + pool;
        }
    }
}
