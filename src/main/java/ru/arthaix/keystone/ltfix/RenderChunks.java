package ru.arthaix.keystone.ltfix;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;

import com.creativemd.littletiles.client.render.cache.RenderingThread;
import com.creativemd.littletiles.client.render.world.RenderUtils;
import com.creativemd.littletiles.common.tileentity.TileEntityLittleTiles;

import net.minecraft.client.renderer.ViewFrustum;
import net.minecraft.client.renderer.chunk.RenderChunk;

/**
 * LittleTiles marks the RenderChunk it looked up when the tile entity was queued. After a renderer reload (F3+A, render
 * distance or video settings, world join) that RenderChunk belongs to the old, discarded view frustum, so the finished
 * geometry waited for an unrelated rebuild. The chunk that shows the tile entity now is marked as well.
 */
public final class RenderChunks {
    private static final AtomicLong REMARKED = new AtomicLong();
    private static Field teField;
    private static Field chunkField;
    private static Field subWorldField;
    private static volatile boolean broken;

    private RenderChunks() {
    }

    /** LittleTiles rendering thread, after RenderingThread.finish accepted a job. */
    public static void afterFinish(Object data) {
        if (broken || data == null) {
            return;
        }
        try {
            init(data.getClass());
            if (subWorldField.getBoolean(data)) {
                return;
            }
            TileEntityLittleTiles te = (TileEntityLittleTiles) teField.get(data);
            ViewFrustum vf = RenderUtils.getViewFrustum();
            if (te == null || vf == null) {
                return;
            }
            RenderChunk current = RenderUtils.getRenderChunk(vf, te.func_174877_v());
            if (current != null && current != chunkField.get(data)) {
                REMARKED.incrementAndGet();
                RenderingThread.markRenderUpdate(current);
            }
        } catch (Throwable t) {
            broken = true;
            System.err.println("[ltfix] render chunk re-mark disabled: " + t);
            t.printStackTrace();
        }
    }

    private static synchronized void init(Class<?> type) throws NoSuchFieldException {
        if (teField != null) {
            return;
        }
        Field sub = type.getDeclaredField("subWorld");
        Field chunk = type.getDeclaredField("chunk");
        Field te = type.getDeclaredField("te");
        sub.setAccessible(true);
        chunk.setAccessible(true);
        te.setAccessible(true);
        subWorldField = sub;
        chunkField = chunk;
        teField = te;
    }

    public static long remarked() {
        return REMARKED.get();
    }
}
