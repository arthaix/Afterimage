package ru.arthaix.keystone.teunloadbatch;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.fml.common.FMLCommonHandler;

/**
 * Chunk packets ask every tile entity in the chunk for its update tag. For the city's Chisels & Bits and LittleTiles
 * blocks that was most of the server's time whenever a player flew into new chunks (the tags are rebuilt from the tile
 * data each time). The first send keeps the tag's serialized bytes on the tile entity; later sends of an unchanged tile
 * entity put those bytes into the packet as they are (MixinNBTTagCompoundRaw). Any change to the tile entity (markDirty,
 * readFromNBT, position, world, validate/invalidate, and the mods' own edit methods through ltfix) voids the bytes.
 *
 * Only Chisels & Bits and LittleTiles tile entities, and only on a dedicated server: an integrated server hands packet
 * objects to its client without encoding them. The first VERIFY_FIRST reuses and every VERIFY_EVERY-th after that are
 * compared with a freshly written tag; one difference turns the cache off for the rest of the run and is logged.
 * -Dteunloadbatch.tagCache=false turns it off, -Dteunloadbatch.tagCacheMB (768) bounds the bytes held, oldest dropped first.
 *
 * The bytes live on the tile entity only; ORDER holds the tile entities weakly, in the order their bytes were made, for
 * eviction. An entry whose bytes were voided by a change is marked dead and skipped; once a quarter of the budget has
 * died since the last cleaning, ORDER is cleaned of dead entries so that edits do not grow it without bound.
 */
public final class TagCache {
    public static final boolean ENABLED = !"false".equals(System.getProperty("teunloadbatch.tagCache")) && dedicatedServer();
    private static final long BUDGET = Long.getLong("teunloadbatch.tagCacheMB", 768L) << 20;
    private static final int MAX_BODY = 8 << 20;
    private static final long VERIFY_FIRST = 2000;
    private static final long VERIFY_EVERY = 500;
    private static final String[] CLASSES = {
        "mod.chiselsandbits.chiseledblock.TileEntityBlockChiseled",
        "com.creativemd.littletiles.common.tileentity.TileEntityLittleTiles",
    };

    private static final class Stored {
        final WeakReference<TileEntity> te;
        final int length;
        /** the bytes were voided by bump(); their length has been taken off HELD already */
        volatile boolean dead;

        Stored(TileEntity te, int length) {
            this.te = new WeakReference<TileEntity>(te);
            this.length = length;
        }
    }

    private static final ConcurrentLinkedQueue<Stored> ORDER = new ConcurrentLinkedQueue<Stored>();
    private static final AtomicLong HELD = new AtomicLong();
    private static final AtomicLong DEAD_SINCE_CLEAN = new AtomicLong();
    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong MISSES = new AtomicLong();
    private static final AtomicLong HIT_BYTES = new AtomicLong();
    private static final AtomicLong EVICTED = new AtomicLong();
    private static final AtomicLong CLEANED = new AtomicLong();
    private static final AtomicLong VERIFIED = new AtomicLong();
    private static final AtomicLong MISMATCHES = new AtomicLong();
    private static volatile boolean active = ENABLED;
    private static volatile String disabledBy;
    private static volatile Class<?>[] cacheable;
    private static volatile boolean cleaning;

    private TagCache() {
    }

    private static boolean dedicatedServer() {
        try {
            return FMLCommonHandler.instance().getSide().isServer();
        } catch (Throwable t) {
            return false;
        }
    }

    private static Class<?>[] cacheableClasses() {
        Class<?>[] c = cacheable;
        if (c == null) {
            java.util.ArrayList<Class<?>> found = new java.util.ArrayList<Class<?>>();
            for (String name : CLASSES) {
                try {
                    found.add(Class.forName(name, false, TagCache.class.getClassLoader()));
                } catch (Throwable ignored) {
                }
            }
            c = found.toArray(new Class<?>[0]);
            cacheable = c;
        }
        return c;
    }

    private static boolean cacheable(TileEntity te) {
        for (Class<?> c : cacheableClasses()) {
            if (c.isInstance(te)) {
                return !(te instanceof TagCacheVeto) || !((TagCacheVeto) te).teunloadbatch$vetoTagCache();
            }
        }
        return false;
    }

    private static byte[] serialize(NBTTagCompound tag) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(1024);
            DataOutputStream out = new DataOutputStream(bytes);
            ((RawCompound) tag).teunloadbatch$writeBody(out);
            out.flush();
            return bytes.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    /** SPacketChunkData constructor, server thread: the tag to put into the packet for this tile entity. */
    public static NBTTagCompound updateTag(TileEntity te) {
        if (!active || !cacheable(te)) {
            return te.func_189517_E_();
        }
        TagCacheHolder holder = (TagCacheHolder) te;
        int version = holder.teunloadbatch$tagVersion();
        byte[] body = holder.teunloadbatch$tagBody();
        if (body != null && holder.teunloadbatch$tagBodyVersion() == version) {
            long hit = HITS.incrementAndGet();
            HIT_BYTES.addAndGet(body.length);
            if (hit <= VERIFY_FIRST || hit % VERIFY_EVERY == 0) {
                // the reused bytes must be what the tile entity would write now; any difference turns the cache off
                NBTTagCompound fresh = te.func_189517_E_();
                byte[] now = serialize(fresh);
                VERIFIED.incrementAndGet();
                if (now == null || !Arrays.equals(now, body)) {
                    MISMATCHES.incrementAndGet();
                    if (disabledBy == null) {
                        disabledBy = te.getClass().getName() + " at " + te.func_174877_v();
                        System.out.println("[teunloadbatch] tag cache turned off: the cached update tag of " + disabledBy
                            + " differs from a fresh one");
                    }
                    active = false;
                    return fresh;
                }
            }
            NBTTagCompound raw = new NBTTagCompound();
            ((RawCompound) raw).teunloadbatch$setRaw(body);
            return raw;
        }
        MISSES.incrementAndGet();
        NBTTagCompound tag = te.func_189517_E_();
        byte[] fresh = serialize(tag);
        // the tag getter itself may have changed the tile entity (and bumped its version): keep nothing then
        if (fresh != null && fresh.length <= MAX_BODY && holder.teunloadbatch$tagVersion() == version) {
            // whatever the holder had is unaccounted here only if it was never bumped (bump takes it off HELD)
            byte[] old = holder.teunloadbatch$tagBody();
            Object oldOwner = holder.teunloadbatch$tagOwner();
            if (old != null && oldOwner instanceof Stored && !((Stored) oldOwner).dead) {
                ((Stored) oldOwner).dead = true;
                HELD.addAndGet(-old.length);
                DEAD_SINCE_CLEAN.addAndGet(old.length);
            }
            Stored stored = new Stored(te, fresh.length);
            holder.teunloadbatch$setTagBody(fresh, version);
            holder.teunloadbatch$setTagOwner(stored);
            HELD.addAndGet(fresh.length);
            ORDER.add(stored);
            evictOverBudget();
            cleanIfDue();
        }
        return tag;
    }

    /** Any change to a tile entity (server thread or wherever the mod changes it). */
    public static void bump(TileEntity te) {
        TagCacheHolder holder = (TagCacheHolder) te;
        Object owner = holder.teunloadbatch$tagOwner();
        byte[] dropped = holder.teunloadbatch$bumpTagVersion();
        if (dropped != null) {
            HELD.addAndGet(-dropped.length);
            DEAD_SINCE_CLEAN.addAndGet(dropped.length);
        }
        if (owner instanceof Stored) {
            ((Stored) owner).dead = true;
            holder.teunloadbatch$setTagOwner(null);
        }
    }

    private static void evictOverBudget() {
        if (HELD.get() <= BUDGET) {
            return;
        }
        long target = BUDGET - (BUDGET >> 4);
        Stored s;
        while (HELD.get() > target && (s = ORDER.poll()) != null) {
            if (s.dead) {
                continue;
            }
            TileEntity te = s.te.get();
            if (te == null) {
                // collected with its bytes and never bumped (chunk unload does not bump): the accounting follows the queue
                s.dead = true;
                HELD.addAndGet(-s.length);
                continue;
            }
            TagCacheHolder holder = (TagCacheHolder) te;
            if (holder.teunloadbatch$tagOwner() == s) {
                holder.teunloadbatch$setTagBody(null, 0);
                holder.teunloadbatch$setTagOwner(null);
                s.dead = true;
                HELD.addAndGet(-s.length);
                EVICTED.incrementAndGet();
            }
        }
    }

    /** Drops dead entries from ORDER once a quarter of the budget has died since the last cleaning. */
    private static void cleanIfDue() {
        if (DEAD_SINCE_CLEAN.get() < (BUDGET >> 2) || cleaning) {
            return;
        }
        cleaning = true;
        try {
            DEAD_SINCE_CLEAN.set(0L);
            long removed = 0L;
            for (java.util.Iterator<Stored> it = ORDER.iterator(); it.hasNext();) {
                Stored s = it.next();
                if (s.dead || s.te.get() == null) {
                    if (!s.dead) {
                        s.dead = true;
                        HELD.addAndGet(-s.length);
                    }
                    it.remove();
                    removed++;
                }
            }
            CLEANED.addAndGet(removed);
        } finally {
            cleaning = false;
        }
    }

    public static String stats() {
        if (!ENABLED) {
            return "tagcache off";
        }
        return "tagcache hits " + HITS.get() + " (" + (HIT_BYTES.get() >> 20) + "MB) misses " + MISSES.get() + " held " + (HELD.get() >> 20)
            + "MB evicted " + EVICTED.get() + " cleaned " + CLEANED.get() + " verified " + VERIFIED.get()
            + (MISMATCHES.get() > 0 ? " MISMATCH " + MISMATCHES.get() + " (off: " + disabledBy + ")" : "");
    }
}
