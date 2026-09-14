package ru.arthaix.keystone.packetbudget;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayDeque;
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
        /** applied or discarded through flushChunk/dropChunk; skipped when the FIFO reaches it */
        boolean dropped;

        Entry(WorldClient world, int cx, int cz, List<NBTTagCompound> tags) {
            this.world = world;
            this.cx = cx;
            this.cz = cz;
            this.tags = tags;
        }
    }

    private static final ArrayDeque<Entry> QUEUE = new ArrayDeque<Entry>();
    /** the queued entries of each chunk, so a block change packet finds them without walking the whole queue */
    private static final java.util.HashMap<Long, java.util.ArrayList<Entry>> BY_CHUNK = new java.util.HashMap<Long, java.util.ArrayList<Entry>>();

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }
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
        Entry e = new Entry(world, cx, cz, tags);
        QUEUE.addLast(e);
        java.util.ArrayList<Entry> list = BY_CHUNK.get(chunkKey(cx, cz));
        if (list == null) {
            list = new java.util.ArrayList<Entry>(2);
            BY_CHUNK.put(chunkKey(cx, cz), list);
        }
        list.add(e);
    }

    private static void unlink(Entry e) {
        long k = chunkKey(e.cx, e.cz);
        java.util.ArrayList<Entry> list = BY_CHUNK.get(k);
        if (list != null) {
            list.remove(e);
            if (list.isEmpty()) {
                BY_CHUNK.remove(k);
            }
        }
    }

    /** Apply everything still pending for one chunk (client thread). */
    public static void flushChunk(int cx, int cz) {
        java.util.ArrayList<Entry> list = BY_CHUNK.remove(chunkKey(cx, cz));
        if (list == null) {
            return;
        }
        WorldClient current = Minecraft.func_71410_x().field_71441_e;
        for (Entry e : list) {
            e.dropped = true;
            if (e.world == current) {
                applyAll(e);
            }
        }
    }

    /** Drop everything pending for one chunk, e.g. on unload (client thread). */
    public static void dropChunk(int cx, int cz) {
        java.util.ArrayList<Entry> list = BY_CHUNK.remove(chunkKey(cx, cz));
        if (list != null) {
            for (Entry e : list) {
                e.dropped = true;
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
            if (e.dropped) {
                // applied or discarded through flushChunk/dropChunk already
                QUEUE.pollFirst();
                continue;
            }
            if (e.world != current) {
                QUEUE.pollFirst();
                unlink(e);
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
            unlink(e);
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
        return BY_CHUNK.size();
    }
}
