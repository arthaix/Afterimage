package ru.arthaix.keystone.ltfix;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.creativemd.creativecore.client.rendering.RenderBox;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.VertexFormat;

/**
 * One BufferBuilder per LittleTiles rendering thread instead of one per tile entity build and layer. LittleTiles sized
 * each new builder for the quads counted before culling; the thread's builder is replaced when a build needs more than
 * it has, so it settles at the largest build of its thread. The build result is copied out of it before the next build
 * (MixinLayeredRenderBufferCache), so nothing else ever references it.
 */
public final class TileBuilders {
    private static final ThreadLocal<BufferBuilder> BUILDER = new ThreadLocal<BufferBuilder>();
    private static final AtomicLong TAKEN = new AtomicLong();
    private static final AtomicLong ALLOCATED = new AtomicLong();

    private TileBuilders() {
    }

    /** As LayeredRenderBufferCache.createVertexBuffer: a builder with room for the quads of cubes. */
    public static BufferBuilder take(VertexFormat format, List<? extends RenderBox> cubes) {
        int size = 1;
        for (RenderBox cube : cubes) {
            size += cube.countQuads();
        }
        // BufferBuilder(int) allocates 4 * its argument bytes; LittleTiles passed vertex-format bytes * quads
        int ints = format.func_177338_f() * size;
        TAKEN.incrementAndGet();
        BufferBuilder b = BUILDER.get();
        if (b == null || b.func_178966_f().capacity() < ints * 4L) {
            b = new BufferBuilder(ints);
            BUILDER.set(b);
            ALLOCATED.incrementAndGet();
        }
        return b;
    }

    public static String stats() {
        return "tileBuilders " + TAKEN.get() + " allocated " + ALLOCATED.get();
    }
}
