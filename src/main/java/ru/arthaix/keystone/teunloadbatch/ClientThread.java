package ru.arthaix.keystone.teunloadbatch;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Client worlds only. Tile entity lookups from threads other than the client thread (LittleTiles rendering threads ask
 * neighbours whether they hide a face, block models read tile entities on chunk workers) used vanilla's IMMEDIATE mode,
 * which creates a missing tile entity right there: an unsynchronised write into the chunk's tile entity map and the
 * world's tile entity list while the client thread writes them too. That crashed the client (NullPointerException in
 * Chunk.addTileEntity) and corrupted the list. Such lookups now only read; creation is handed to the client thread.
 */
public final class ClientThread {
    private static volatile Thread client;
    private static final Set<BlockPos> PENDING = ConcurrentHashMap.newKeySet();
    private static volatile boolean announced;

    private ClientThread() {
    }

    /** True on the client thread, and also while it is not known yet (then lookups behave as in vanilla). */
    public static boolean isClientThread() {
        Thread t = Thread.currentThread();
        Thread c = client;
        if (c != null) {
            return t == c;
        }
        if ("Client thread".equals(t.getName())) {
            client = t;
        }
        return true;
    }

    /** Another thread wanted a tile entity at pos that does not exist yet: create it on the client thread. */
    public static void createLater(World world, BlockPos pos) {
        BlockPos p = pos.func_185334_h();
        if (!PENDING.add(p)) {
            return;
        }
        if (!announced) {
            announced = true;
            System.out.println("[teunloadbatch] tile entity lookups from thread '" + Thread.currentThread().getName()
                + "' no longer create tile entities there; the client thread creates them");
        }
        Tasks.schedule(world, p);
    }

    static void done(BlockPos p) {
        PENDING.remove(p);
    }

    /** Separate class: only loaded in a client JVM. */
    static final class Tasks {
        static void schedule(World world, BlockPos p) {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.func_71410_x();
            mc.func_152344_a(() -> {
                ClientThread.done(p);
                if (mc.field_71441_e == world) {
                    world.func_175625_s(p);
                }
            });
        }
    }
}
