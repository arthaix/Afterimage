package ru.arthaix.keystone.ltfix;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

import com.creativemd.littletiles.client.render.cache.RenderingThread;
import com.creativemd.littletiles.common.tileentity.TileEntityLittleTiles;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

/**
 * LittleTiles rendering jobs whose tile entity is gone. On the client TileEntityLittleTiles.onChunkUnload sets its tile
 * list to null, so a job queued before its chunk unloaded reports "not loaded yet" forever; retrying it spun the
 * rendering threads (over a million retries in a few minutes after teleports). Such jobs are finished as dropped.
 */
public final class RenderJobs {
    private static final AtomicLong DROPPED = new AtomicLong();
    private static Field teField;
    private static Method finish;
    private static volatile boolean broken;

    private RenderJobs() {
    }

    /** LittleTiles rendering thread: true when the job was dropped because its tile entity is no longer in a loaded chunk. */
    public static boolean dropIfDead(Object data) {
        if (broken || data == null) {
            return false;
        }
        try {
            init(data.getClass());
            TileEntityLittleTiles te = (TileEntityLittleTiles) teField.get(data);
            if (te == null || !isDead(te)) {
                return false;
            }
            finish.invoke(null, data, -1, true);
            DROPPED.incrementAndGet();
            return true;
        } catch (Throwable t) {
            broken = true;
            System.err.println("[ltfix] dropping dead rendering jobs disabled: " + t);
            t.printStackTrace();
            return false;
        }
    }

    private static boolean isDead(TileEntityLittleTiles te) {
        World world = te.func_145831_w();
        if (world == null || te.func_145837_r()) {
            return true;
        }
        BlockPos pos = te.func_174877_v();
        Chunk chunk = world.func_72863_F().func_186026_b(pos.func_177958_n() >> 4, pos.func_177952_p() >> 4);
        // CHECK only reads the chunk's tile entity map
        return chunk == null || chunk.func_177424_a(pos, Chunk.EnumCreateEntityType.CHECK) != te;
    }

    private static synchronized void init(Class<?> type) throws ReflectiveOperationException {
        if (finish != null) {
            return;
        }
        Field te = type.getDeclaredField("te");
        te.setAccessible(true);
        Method m = RenderingThread.class.getDeclaredMethod("finish", type, int.class, boolean.class);
        m.setAccessible(true);
        teField = te;
        finish = m;
    }

    public static long dropped() {
        return DROPPED.get();
    }
}
