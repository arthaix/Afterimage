package ru.arthaix.keystone.ltfix;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.creativemd.littletiles.common.tile.LittleTile;
import com.creativemd.littletiles.common.util.compression.LittleNBTCompressionTools;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import ru.arthaix.keystone.teunloadbatch.NbtAttachment;

/**
 * Turning a LittleTiles tile entity's NBT into tiles (LittleNBTCompressionTools.readTiles) ran on the client thread when
 * packetbudget applied the chunk's tags: 13-17% of that thread during flights over the city. Right after the network
 * thread decodes a chunk packet (teunloadbatch ChunkTagsDecoded), -Dltfix.preparseThreads (3) background threads read
 * every "tiles" list of the LittleTiles tags (the tile entity's own and its structures' "children") and attach the tiles
 * to that list object. When the client thread reaches the tag, readTiles takes the attached tiles instead of parsing
 * (MixinLittleNBTCompressionTools); a list not parsed yet is parsed there as before. Reading is only construction of new
 * tile objects from NBT, which neither thread changes. -Dltfix.preparse=false turns it off.
 */
public final class LtPreparse {
    public static final boolean ENABLED = !"false".equals(System.getProperty("ltfix.preparse"));
    private static final int THREADS = Math.max(1, Integer.getInteger("ltfix.preparseThreads", 3));
    private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(THREADS, THREADS, 30L, TimeUnit.SECONDS,
        new LinkedBlockingQueue<Runnable>(), r -> {
            Thread t = new Thread(r, "ltfix tile preparse");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY + 1);
            return t;
        });
    private static final AtomicLong CHUNKS = new AtomicLong();
    private static final AtomicLong LISTS = new AtomicLong();
    private static final AtomicLong NANOS = new AtomicLong();
    private static final AtomicLong USED = new AtomicLong();
    private static final AtomicLong FAILED = new AtomicLong();

    static {
        POOL.allowCoreThreadTimeOut(true);
    }

    private LtPreparse() {
    }

    /** Network thread: the tile entity tags of one chunk packet. */
    public static void submit(List<NBTTagCompound> tags) {
        if (!ENABLED) {
            return;
        }
        final List<NBTTagCompound> lt = new ArrayList<NBTTagCompound>();
        for (NBTTagCompound tag : tags) {
            if (tag.func_150297_b("content", 10)) {
                lt.add(tag);
            }
        }
        if (lt.isEmpty()) {
            return;
        }
        CHUNKS.incrementAndGet();
        POOL.execute(() -> {
            for (NBTTagCompound tag : lt) {
                walk(tag.func_74775_l("content"));
            }
        });
    }

    private static void walk(NBTTagCompound list) {
        if (list.func_150297_b("tiles", 9)) {
            NBTTagList tiles = list.func_150295_c("tiles", 10);
            NbtAttachment attach = (NbtAttachment) tiles;
            if (tiles.func_74745_c() > 0 && attach.teunloadbatch$peekAttachment() == null) {
                long t0 = System.nanoTime();
                try {
                    List<LittleTile> parsed = LittleNBTCompressionTools.readTiles(tiles);
                    attach.teunloadbatch$setAttachment(new Preparsed(parsed));
                    LISTS.incrementAndGet();
                } catch (Throwable t) {
                    FAILED.incrementAndGet();
                }
                NANOS.addAndGet(System.nanoTime() - t0);
            }
        }
        if (list.func_150297_b("children", 9)) {
            NBTTagList children = list.func_150295_c("children", 10);
            for (int i = 0, n = children.func_74745_c(); i < n; i++) {
                walk(children.func_150305_b(i));
            }
        }
    }

    /** Wrapper so nothing else that attaches to an NBTTagList is mistaken for parsed tiles. */
    public static final class Preparsed {
        final List<LittleTile> tiles;

        Preparsed(List<LittleTile> tiles) {
            this.tiles = tiles;
        }
    }

    /** readTiles HEAD, any thread: the tiles parsed in the background for exactly this list, or null. */
    public static List<LittleTile> take(NBTTagList list) {
        if (!ENABLED) {
            return null;
        }
        Object a = ((NbtAttachment) list).teunloadbatch$takeAttachment();
        if (a instanceof Preparsed) {
            USED.incrementAndGet();
            return ((Preparsed) a).tiles;
        }
        return null;
    }

    public static String stats() {
        if (!ENABLED) {
            return "preparse off";
        }
        return "preparse chunks " + CHUNKS.get() + " lists " + LISTS.get() + " used " + USED.get() + " " + (NANOS.get() / 1_000_000L) + "ms queue "
            + POOL.getQueue().size() + (FAILED.get() > 0 ? " FAILED " + FAILED.get() : "");
    }
}
