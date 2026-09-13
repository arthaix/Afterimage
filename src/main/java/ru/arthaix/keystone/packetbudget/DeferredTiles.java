package ru.arthaix.keystone.packetbudget;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;

/**
 * Tile-entity payloads of received chunks, applied in time slices on the client thread.
 *
 * A dense Chisels&Bits / LittleTiles chunk carries thousands of tile-entity tags and
 * takes ~40 ms to apply; vanilla applies them all inside the chunk packet handler, which
 * at 300 fps is a ten-frame freeze. Here the handler only stores the list and the block
 * data goes in immediately; the tags are then applied FIFO, up to TE_BUDGET_NANOS per
 * frame. Any packet that touches a chunk with pending tags flushes that chunk first, so
 * later updates never get overwritten by older deferred data.
 */
public final class DeferredTiles {
    private static final class Entry {
        final WorldClient world;
        final int cx, cz;
        final List<NBTTagCompound> tags;
        int index;

        Entry(WorldClient world, int cx, int cz, List<NBTTagCompound> tags) {
            this.world = world;
            this.cx = cx;
            this.cz = cz;
            this.tags = tags;
        }
    }

    private static final ArrayDeque<Entry> QUEUE = new ArrayDeque<Entry>();
    /** TileEntity.handleUpdateTag(NBTTagCompound) is a Forge-added method with a plain name at runtime. */
    private static final MethodHandle HANDLE_UPDATE_TAG;
    static {
        MethodHandle h;
        try {
            h = MethodHandles.publicLookup().findVirtual(TileEntity.class, "handleUpdateTag", MethodType.methodType(void.class, NBTTagCompound.class));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("TileEntity.handleUpdateTag not found", e);
        }
        HANDLE_UPDATE_TAG = h;
    }

    private DeferredTiles() {
    }

    /** Called from the chunk packet handler (client thread). */
    public static void enqueue(WorldClient world, int cx, int cz, List<NBTTagCompound> tags) {
        // no world: a chunk packet still queued while leaving the server; an entry without one would match
        // Minecraft.world == null in process() and be applied to nothing (crash)
        if (world == null || tags == null || tags.isEmpty()) {
            return;
        }
        QUEUE.addLast(new Entry(world, cx, cz, tags));
    }

    /** Apply everything still pending for one chunk (client thread). */
    public static void flushChunk(int cx, int cz) {
        if (QUEUE.isEmpty()) {
            return;
        }
        WorldClient current = Minecraft.func_71410_x().field_71441_e;
        for (Iterator<Entry> it = QUEUE.iterator(); it.hasNext();) {
            Entry e = it.next();
            if (e.cx == cx && e.cz == cz) {
                it.remove();
                if (e.world == current) {
                    applyAll(e);
                }
            }
        }
    }

    /** Drop everything pending for one chunk, e.g. on unload (client thread). */
    public static void dropChunk(int cx, int cz) {
        for (Iterator<Entry> it = QUEUE.iterator(); it.hasNext();) {
            Entry e = it.next();
            if (e.cx == cx && e.cz == cz) {
                it.remove();
            }
        }
    }

    /** Once per frame, before the packet drain. */
    public static void process(long budgetNanos) {
        if (QUEUE.isEmpty()) {
            return;
        }
        WorldClient current = Minecraft.func_71410_x().field_71441_e;
        long deadline = System.nanoTime() + budgetNanos;
        while (!QUEUE.isEmpty()) {
            Entry e = QUEUE.peekFirst();
            if (e.world != current) {
                QUEUE.pollFirst();
                continue;
            }
            List<NBTTagCompound> tags = e.tags;
            while (e.index < tags.size()) {
                applyOne(e.world, tags.get(e.index++));
                if (System.nanoTime() >= deadline) {
                    return;
                }
            }
            QUEUE.pollFirst();
        }
    }

    private static void applyAll(Entry e) {
        List<NBTTagCompound> tags = e.tags;
        while (e.index < tags.size()) {
            applyOne(e.world, tags.get(e.index++));
        }
    }

    /** Exactly what vanilla NetHandlerPlayClient.handleChunkData does per tag. */
    private static void applyOne(WorldClient world, NBTTagCompound nbt) {
        BlockPos pos = new BlockPos(nbt.func_74762_e("x"), nbt.func_74762_e("y"), nbt.func_74762_e("z"));
        TileEntity te = world.func_175625_s(pos);
        if (te != null) {
            try {
                HANDLE_UPDATE_TAG.invoke(te, nbt);
            } catch (Throwable ex) {
                PacketBudget.LOG.warn("[packetbudget] tile entity at {} rejected its update tag", pos, ex);
            }
        }
    }

    public static int pending() {
        return QUEUE.size();
    }
}
