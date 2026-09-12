package ru.arthaix.afterimage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.ViewFrustum;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

/**
 * Afterimage phase 2: persistent far zone.
 *
 * Write: every section upload whose content fingerprint differs from what is on disk is copied (memcpy) and
 * written by a background thread as minecraft/afterimage/cache/<server>/DIM<n>/r.<rx>.<rz>/<cx>.<sy>.<cz>.L<layer>.aimg
 * (header + deflate). Writes go through a temp file and an atomic rename; a newer upload for the same section
 * supersedes a queued older one. A layer that stays empty for 30 s after vanilla compiled its loaded chunk is
 * deleted.
 * Load: on joining a world the cache of that server and dimension is scanned in the background, nearest sections
 * first, up to the far-zone VRAM budget, inflated off-thread and handed to the main thread, which uploads them
 * to GPU buffers for at most 4 ms per frame. Sections vanilla already shows, or that have a live in-memory copy,
 * are not taken from disk.
 */
public final class Disk {
    public static volatile boolean ENABLED = !"false".equals(System.getProperty("afterimage.disk"));

    private static final int MAGIC = 0x41494D47;
    /** 2 adds the geometry tick; version 1 files are still read (tick unknown). */
    private static final int VERSION = 2;
    private static final int VS = 28;
    private static final long DELETE_GRACE = 30_000_000_000L;
    private static final long WRITE_BACKLOG_MAX = 1L << 30;
    private static final long READY_MAX = 512L << 20;
    private static final long UPLOAD_BUDGET_NANOS = Long.getLong("afterimage.diskUploadMs", 4L) * 1_000_000L;

    private static File root;
    private static volatile File worldDir;
    private static volatile int generation;
    private static boolean scanPending;

    private static final class Job {
        final long sk;
        final long key;
        final int layer;
        final byte[] data;
        final long exact;
        final long ms;
        final File dir;
        final int gen;

        final long geomTime;

        Job(long sk, long key, int layer, byte[] data, long exact, long ms, long geomTime, File dir, int gen) {
            this.geomTime = geomTime;
            this.sk = sk;
            this.key = key;
            this.layer = layer;
            this.data = data;
            this.exact = exact;
            this.ms = ms;
            this.dir = dir;
            this.gen = gen;
        }
    }

    private static final class Loaded {
        final long key;
        final int layer;
        final int x;
        final int y;
        final int z;
        final ByteBuffer data;
        final int gen;

        final long geomTime;

        Loaded(long key, int layer, int x, int y, int z, ByteBuffer data, long geomTime, int gen) {
            this.geomTime = geomTime;
            this.key = key;
            this.layer = layer;
            this.x = x;
            this.y = y;
            this.z = z;
            this.data = data;
            this.gen = gen;
        }
    }

    private static final class Meta {
        final File file;
        final long key;
        final int layer;
        final int x;
        final int y;
        final int z;
        final int raw;
        final int comp;
        double dist2;

        final long geomTime;
        final int headerLen;

        Meta(File file, long key, int layer, int x, int y, int z, int raw, int comp, long geomTime, int headerLen) {
            this.geomTime = geomTime;
            this.headerLen = headerLen;
            this.file = file;
            this.key = key;
            this.layer = layer;
            this.x = x;
            this.y = y;
            this.z = z;
            this.raw = raw;
            this.comp = comp;
        }
    }

    /** section key * 4 + layer -> multiset fingerprint of what is on disk. */
    private static final ConcurrentHashMap<Long, Long> ONDISK = new ConcurrentHashMap<Long, Long>(65536);
    /** section key * 4 + layer -> client world tick of the geometry on disk (0 = unknown, version 1 file). */
    private static final ConcurrentHashMap<Long, Long> ONDISK_TIME = new ConcurrentHashMap<Long, Long>(65536);

    // ---- server sync state (client thread) ----
    private static final long SYNC_SAFETY_TICKS = 1200;
    private static volatile boolean awaitingServer;
    private static long awaitSince;
    private static long lastSync;
    private static long lastSyncWritten;
    private static long lastSyncWriteNanos;
    private static volatile String syncWorldId;
    private static final AtomicLong INVALIDATED_FILES = new AtomicLong();
    private static final AtomicLong OUTDATED_SKIPPED = new AtomicLong();
    private static final ConcurrentHashMap<Long, Job> LATEST = new ConcurrentHashMap<Long, Job>();
    private static final HashMap<Long, Long> PENDING_DELETE = new HashMap<Long, Long>();

    /** An upload waiting for vanilla to confirm that its RenderChunk still shows that position. */
    private static final class Held {
        final long key;
        final byte[] data;
        final long exact;
        final long ms;
        final long geomTime;

        Held(long key, byte[] data, long exact, long ms, long geomTime) {
            this.key = key;
            this.data = data;
            this.exact = exact;
            this.ms = ms;
            this.geomTime = geomTime;
        }
    }

    /** RenderChunk -> uploads per layer not yet confirmed (client thread only) */
    private static final IdentityHashMap<RenderChunk, Held[]> HELD = new IdentityHashMap<RenderChunk, Held[]>();
    private static final long HELD_MAX = 256L << 20;
    private static long heldBytes;
    private static long heldCommitted;
    private static long heldDropped;
    private static final ConcurrentLinkedQueue<Loaded> READY = new ConcurrentLinkedQueue<Loaded>();

    private static final AtomicLong BACKLOG = new AtomicLong();
    private static final AtomicLong READY_BYTES = new AtomicLong();
    private static final AtomicLong WRITTEN_FILES = new AtomicLong();
    private static final AtomicLong WRITTEN_RAW = new AtomicLong();
    private static final AtomicLong WRITTEN_COMP = new AtomicLong();
    private static final AtomicLong DELETED = new AtomicLong();
    private static final AtomicLong SCANNED = new AtomicLong();
    private static final AtomicLong SCANNED_COMP = new AtomicLong();
    private static final AtomicLong LOADED_FILES = new AtomicLong();
    private static final AtomicLong LOADED_RAW = new AtomicLong();
    private static final AtomicLong OVER_BUDGET = new AtomicLong();
    private static final AtomicLong ERRORS = new AtomicLong();
    private static volatile String status = "idle";
    private static long uploadedFromDisk;
    private static long rejectedFromDisk;

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Afterimage Disk Writer");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    });
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Afterimage Disk Loader");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    });

    private Disk() {
    }

    public static void init(File cacheRoot) {
        root = cacheRoot;
        root.mkdirs();
    }

    // ================= world lifecycle (main thread) =================

    public static void onWorld(WorldClient w) {
        flushSync();
        generation++;
        ONDISK.clear();
        LATEST.clear();
        PENDING_DELETE.clear();
        HELD.clear();
        heldBytes = 0L;
        Loaded l;
        while ((l = READY.poll()) != null) {
            READY_BYTES.addAndGet(-l.data.capacity());
        }
        scanPending = false;
        if (w == null || root == null) {
            worldDir = null;
            status = "no world";
            return;
        }
        ONDISK_TIME.clear();
        lastSync = 0L;
        lastSyncWritten = 0L;
        syncWorldId = null;
        if (ClientSync.serverHasAfterimage()) {
            // the server tracks chunk changes: wait for its world id and change list before touching the cache
            worldDir = null;
            awaitingServer = true;
            awaitSince = System.nanoTime();
            status = "waiting for server";
            return;
        }
        awaitingServer = false;
        File dir = new File(root, serverId() + File.separator + "DIM" + w.field_73011_w.func_186058_p().func_186068_a());
        dir.mkdirs();
        worldDir = dir;
        scanPending = true;
        status = "waiting for player";
    }

    /** Client thread, on the server's Hello: use the cache of that world and dimension; returns the sync point to ask for. */
    public static long beginServerWorld(String worldId, int dim) {
        flushSync();
        generation++;
        ONDISK.clear();
        ONDISK_TIME.clear();
        LATEST.clear();
        PENDING_DELETE.clear();
        HELD.clear();
        heldBytes = 0L;
        Loaded l;
        while ((l = READY.poll()) != null) {
            READY_BYTES.addAndGet(-l.data.capacity());
        }
        scanPending = false;
        if (root == null) {
            return 0L;
        }
        String id = worldId.replaceAll("[^A-Za-z0-9._-]", "_");
        File server = new File(root, serverId());
        File dir = new File(server, id + File.separator + "DIM" + dim);
        File legacy = new File(server, "DIM" + dim);
        if (!dir.exists() && legacy.isDirectory()) {
            // a cache made before the server had Afterimage belongs to the world the server runs now
            try {
                dir.getParentFile().mkdirs();
                Files.move(legacy.toPath(), dir.toPath());
                Capture.logInfo("disk: adopted the cache in " + legacy + " for world " + id);
            } catch (Throwable t) {
                Capture.logError("disk.adopt", t);
            }
        }
        dir.mkdirs();
        worldDir = dir;
        syncWorldId = id;
        lastSync = readSync(dir);
        lastSyncWritten = lastSync;
        awaitingServer = true;
        awaitSince = System.nanoTime();
        status = "syncing with server";
        return Math.max(0L, lastSync - SYNC_SAFETY_TICKS);
    }

    /** Client thread: the server's answer to Sync has been applied; the cache may be loaded now. */
    public static void serverSyncDone() {
        awaitingServer = false;
        if (worldDir != null) {
            scanPending = true;
            status = "waiting for player";
        }
    }

    /** Client thread: every server change up to serverTime has been applied. */
    public static void noteSync(long serverTime) {
        if (serverTime <= lastSync) {
            return;
        }
        lastSync = serverTime;
        if (System.nanoTime() - lastSyncWriteNanos > 10_000_000_000L) {
            flushSync();
        }
    }

    private static void flushSync() {
        final File dir = worldDir;
        final long value = lastSync;
        if (dir == null || syncWorldId == null || value <= lastSyncWritten) {
            return;
        }
        lastSyncWritten = value;
        lastSyncWriteNanos = System.nanoTime();
        // queued behind the deletions this sync point covers
        WRITER.submit(() -> writeSync(dir, value));
    }

    private static long readSync(File dir) {
        File f = new File(dir, "sync.txt");
        try {
            if (f.isFile()) {
                return Long.parseLong(new String(Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.US_ASCII).trim());
            }
        } catch (Throwable t) {
            Capture.logError("disk.readSync", t);
        }
        return 0L;
    }

    private static void writeSync(File dir, long value) {
        try {
            File tmp = new File(dir, "sync.txt.tmp");
            Files.write(tmp.toPath(), Long.toString(value).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            Files.move(tmp.toPath(), new File(dir, "sync.txt").toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            Capture.logError("disk.writeSync", t);
        }
    }

    private static long chunkKeyOf(long sectionKey) {
        BlockPos p = BlockPos.func_177969_a(sectionKey);
        return ChunkPos.func_77272_a(p.func_177958_n() >> 4, p.func_177952_p() >> 4);
    }

    /** Client thread: delete cached layers of chunk (cx, cz) whose geometry predates server tick t. */
    public static void invalidateChunk(int cx, int cz, long t) {
        final File dir = worldDir;
        if (dir == null) {
            return;
        }
        final int gen = generation;
        for (int sy = 0; sy < 16; sy++) {
            long key = new BlockPos(cx << 4, sy << 4, cz << 4).func_177986_g();
            for (int l = 0; l < 4; l++) {
                final long sk = key * 4 + l;
                Long dt = ONDISK_TIME.get(sk);
                if (dt == null || dt + Far.SYNC_TOLERANCE_TICKS >= t) {
                    continue;
                }
                Job q = LATEST.get(sk);
                if (q != null && q.geomTime + Far.SYNC_TOLERANCE_TICKS >= t) {
                    continue;
                }
                PENDING_DELETE.remove(sk);
                ONDISK_TIME.remove(sk);
                INVALIDATED_FILES.incrementAndGet();
                final long tt = t;
                WRITER.submit(() -> deleteIfOlder(dir, gen, sk, tt));
            }
        }
    }

    private static void deleteIfOlder(File dir, int gen, long sk, long t) {
        try {
            if (dir == null || gen != generation || LATEST.containsKey(sk)) {
                return;
            }
            File f = fileFor(dir, sk >> 2, (int) (sk & 3));
            Meta m = f.isFile() ? readHeader(f) : null;
            if (m != null && m.geomTime + Far.SYNC_TOLERANCE_TICKS < t && f.delete()) {
                DELETED.incrementAndGet();
                ONDISK.remove(sk);
                ONDISK_TIME.remove(sk);
            }
        } catch (Throwable e) {
            ERRORS.incrementAndGet();
            Capture.logError("disk.invalidate", e);
        }
    }

    public static void tick(long now) {
        if (awaitingServer && now - awaitSince > 20_000_000_000L) {
            awaitingServer = false;
            Minecraft mcw = Minecraft.func_71410_x();
            if (worldDir == null && mcw.field_71441_e != null && root != null) {
                File dir = new File(root, serverId() + File.separator + "DIM" + mcw.field_71441_e.field_73011_w.func_186058_p().func_186068_a());
                dir.mkdirs();
                worldDir = dir;
                Capture.logInfo("disk: no answer from the server's Afterimage within 20 s, using the local cache");
            }
            if (worldDir != null) {
                scanPending = true;
                status = "waiting for player";
            }
        }
        if (lastSync > lastSyncWritten && now - lastSyncWriteNanos > 10_000_000_000L) {
            flushSync();
        }
        if (!ENABLED || worldDir == null) {
            return;
        }
        Minecraft mc = Minecraft.func_71410_x();
        if (scanPending && mc.field_71439_g != null) {
            scanPending = false;
            EntityPlayer p = mc.field_71439_g;
            final double px = p.field_70165_t;
            final double py = p.field_70163_u;
            final double pz = p.field_70161_v;
            final int gen = generation;
            final File dir = worldDir;
            final long budget = Far.budget();
            final Long2LongOpenHashMap changes = Far.changesSnapshot();
            LOADER.submit(() -> loadAll(dir, gen, px, py, pz, budget, changes));
        }
        if (!PENDING_DELETE.isEmpty()) {
            for (Iterator<Map.Entry<Long, Long>> it = PENDING_DELETE.entrySet().iterator(); it.hasNext();) {
                Map.Entry<Long, Long> e = it.next();
                if (now < e.getValue()) {
                    continue;
                }
                it.remove();
                final long sk = e.getKey();
                final File dir = worldDir;
                final int gen = generation;
                WRITER.submit(() -> deleteFile(dir, gen, sk));
            }
        }
    }

    // ================= write side =================

    /** Main thread, from Capture.onUpload. layer 0..2, 28-byte vertex format. */
    /**
     * Client thread, from Capture.onUpload. An upload is written only when its RenderChunk vouches for it at its current
     * position: it already has a compiled chunk there (a rebuild in place, a translucent resort, a LittleTiles re-upload),
     * or vanilla later sets a compiled chunk with that layer for the same position. Vanilla queues uploads for the client
     * thread; one queued before the RenderChunk moved arrives under the new position with the old position's geometry.
     * Such uploads are held and dropped when the RenderChunk moves again, frees its buffers, or compiles without that layer.
     */
    public static void onSectionUpload(RenderChunk rc, long key, int layer, ByteBuffer buf, int size, long exact, long ms, long geomTime) {
        CompiledChunk cc = rc.func_178571_g();
        // A RenderChunk that has a compiled chunk still shows the position it was built for, so its uploads belong there.
        // The layer flag is not checked: LittleTiles uploads its tiles into a layer first and marks the layer used after.
        if (cc != null && cc != CompiledChunk.field_178502_a) {
            commitUpload(key, layer, buf, null, size, exact, ms, geomTime);
            return;
        }
        if (!ENABLED || worldDir == null || size <= 0 || heldBytes + size > HELD_MAX) {
            return;
        }
        Held[] held = HELD.get(rc);
        if (held == null) {
            held = new Held[4];
            HELD.put(rc, held);
        }
        if (held[layer] != null) {
            heldBytes -= held[layer].data.length;
        }
        byte[] data = new byte[size];
        buf.duplicate().get(data);
        held[layer] = new Held(key, data, exact, ms, geomTime);
        heldBytes += size;
    }

    /** RenderChunk.setCompiledChunk HEAD: vanilla confirmed what this RenderChunk shows at its position. */
    public static void onCompiled(RenderChunk rc, CompiledChunk next) {
        if (!Capture.onMainThread()) {
            return;
        }
        Held[] held = HELD.remove(rc);
        if (held == null) {
            return;
        }
        long key = rc.func_178568_j().func_177986_g();
        BlockRenderLayer[] layers = BlockRenderLayer.values();
        for (int l = 0; l < held.length; l++) {
            Held h = held[l];
            if (h == null) {
                continue;
            }
            heldBytes -= h.data.length;
            if (next != null && next != CompiledChunk.field_178502_a && h.key == key && !next.func_178491_b(layers[l])) {
                commitUpload(h.key, l, null, h.data, h.data.length, h.exact, h.ms, h.geomTime);
                heldCommitted++;
            } else {
                heldDropped++;
            }
        }
    }

    /** RenderChunk.setPosition / deleteGlResources HEAD: uploads held for it belong nowhere now. */
    public static void dropHeld(RenderChunk rc) {
        if (!Capture.onMainThread()) {
            return;
        }
        Held[] held = HELD.remove(rc);
        if (held == null) {
            return;
        }
        for (Held h : held) {
            if (h != null) {
                heldBytes -= h.data.length;
                heldDropped++;
            }
        }
    }

    private static void commitUpload(long key, int layer, ByteBuffer buf, byte[] heldData, int size, long exact, long ms, long geomTime) {
        File dir = worldDir;
        if (!ENABLED || dir == null || size <= 0) {
            return;
        }
        long sk = key * 4 + layer;
        PENDING_DELETE.remove(sk);
        Job queued = LATEST.get(sk);
        if (queued != null) {
            if (queued.ms == ms) {
                return;
            }
        } else {
            Long d = ONDISK.get(sk);
            if (d != null && d == ms) {
                Long dt = ONDISK_TIME.get(sk);
                if (dt == null || !Far.outdated(key, dt)) {
                    return;
                }
                // same bytes, but the file's tick predates a server change: rewrite it with the current tick
            }
        }
        if (BACKLOG.get() + size > WRITE_BACKLOG_MAX) {
            return;
        }
        byte[] data = heldData;
        if (data == null) {
            data = new byte[size];
            buf.duplicate().get(data);
        }
        final Job job = new Job(sk, key, layer, data, exact, ms, geomTime, dir, generation);
        LATEST.put(sk, job);
        BACKLOG.addAndGet(size);
        WRITER.submit(() -> write(job));
    }

    /** Multiset fingerprint of the layer as stored on disk, 0 when not on disk. */
    public static long diskHash(long key, int layer) {
        Long v = ONDISK.get(key * 4 + layer);
        return v == null ? 0L : v;
    }

    /** Main thread, from Far.onCompiledReplace when the chunk is loaded and vanilla compiled the section. */
    public static void onVanillaCompiled(long key, CompiledChunk next) {
        if (!ENABLED || worldDir == null || next == null) {
            return;
        }
        BlockRenderLayer[] layers = BlockRenderLayer.values();
        long due = System.nanoTime() + DELETE_GRACE;
        for (int l = 0; l < 4; l++) {
            long sk = key * 4 + l;
            if (next.func_178491_b(layers[l]) && ONDISK.containsKey(sk)) {
                PENDING_DELETE.put(sk, due);
            }
        }
    }

    private static File fileFor(File dir, long key, int layer) {
        BlockPos p = BlockPos.func_177969_a(key);
        int cx = p.func_177958_n() >> 4;
        int sy = p.func_177956_o() >> 4;
        int cz = p.func_177952_p() >> 4;
        return new File(dir, "r." + (cx >> 5) + "." + (cz >> 5) + File.separator + cx + "." + sy + "." + cz + ".L" + layer + ".aimg");
    }

    private static void write(Job j) {
        try {
            if (LATEST.get(j.sk) != j || j.gen != generation) {
                return;
            }
            File f = fileFor(j.dir, j.key, j.layer);
            f.getParentFile().mkdirs();
            Deflater def = new Deflater(1);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(j.data.length / 2 + 64);
            try {
                def.setInput(j.data);
                def.finish();
                byte[] chunk = new byte[1 << 16];
                while (!def.finished()) {
                    int n = def.deflate(chunk);
                    bos.write(chunk, 0, n);
                }
            } finally {
                def.end();
            }
            BlockPos p = BlockPos.func_177969_a(j.key);
            File tmp = new File(f.getPath() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(j.layer);
                out.writeInt(p.func_177958_n());
                out.writeInt(p.func_177956_o());
                out.writeInt(p.func_177952_p());
                out.writeInt(VS);
                out.writeInt(j.data.length);
                out.writeLong(j.exact);
                out.writeLong(j.ms);
                out.writeLong(j.geomTime);
                out.writeInt(bos.size());
                bos.writeTo(out);
            }
            try {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFailed) {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            ONDISK.put(j.sk, j.ms);
            ONDISK_TIME.put(j.sk, j.geomTime);
            WRITTEN_FILES.incrementAndGet();
            WRITTEN_RAW.addAndGet(j.data.length);
            WRITTEN_COMP.addAndGet(bos.size());
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            Capture.logError("disk.write", t);
        } finally {
            LATEST.remove(j.sk, j);
            BACKLOG.addAndGet(-j.data.length);
        }
    }

    private static void deleteFile(File dir, int gen, long sk) {
        try {
            if (dir == null || gen != generation || LATEST.containsKey(sk)) {
                return;
            }
            File f = fileFor(dir, sk >> 2, (int) (sk & 3));
            if (f.delete()) {
                DELETED.incrementAndGet();
            }
            ONDISK.remove(sk);
            ONDISK_TIME.remove(sk);
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            Capture.logError("disk.delete", t);
        }
    }

    public static void clearWorld() {
        final File dir = worldDir;
        if (dir == null) {
            return;
        }
        generation++;
        ONDISK.clear();
        LATEST.clear();
        PENDING_DELETE.clear();
        HELD.clear();
        heldBytes = 0L;
        WRITER.submit(() -> {
            deleteTree(dir);
            dir.mkdirs();
        });
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        f.delete();
    }

    // ================= load side =================

    private static void loadAll(File dir, int gen, double px, double py, double pz, long budget, Long2LongOpenHashMap changes) {
        try {
            status = "scanning";
            ArrayList<Meta> metas = new ArrayList<Meta>();
            File[] regions = dir.listFiles();
            if (regions != null) {
                for (File r : regions) {
                    if (gen != generation) {
                        return;
                    }
                    if (!r.isDirectory() || !r.getName().startsWith("r.")) {
                        continue;
                    }
                    File[] files = r.listFiles();
                    if (files == null) {
                        continue;
                    }
                    for (File f : files) {
                        String name = f.getName();
                        if (!name.endsWith(".aimg")) {
                            if (name.endsWith(".tmp")) {
                                f.delete();
                            }
                            continue;
                        }
                        Meta m = readHeader(f);
                        if (m == null) {
                            continue;
                        }
                        long ct = changes.get(chunkKeyOf(m.key));
                        if (ct != 0L && ct > m.geomTime + Far.SYNC_TOLERANCE_TICKS) {
                            // the server changed this chunk after the geometry was made
                            final long sk = m.key * 4 + m.layer;
                            WRITER.submit(() -> deleteIfOlder(dir, gen, sk, ct));
                            INVALIDATED_FILES.incrementAndGet();
                            continue;
                        }
                        metas.add(m);
                        ONDISK.put(m.key * 4 + m.layer, readHash(f));
                        ONDISK_TIME.put(m.key * 4 + m.layer, m.geomTime);
                        SCANNED.incrementAndGet();
                        SCANNED_COMP.addAndGet(m.comp);
                    }
                }
            }
            for (Meta m : metas) {
                double dx = m.x + 8 - px;
                double dy = m.y + 8 - py;
                double dz = m.z + 8 - pz;
                m.dist2 = dx * dx + dy * dy + dz * dz;
            }
            Collections.sort(metas, (a, b) -> Double.compare(a.dist2, b.dist2));
            status = "loading " + metas.size() + " files";
            long used = 0;
            for (Meta m : metas) {
                if (gen != generation) {
                    return;
                }
                if (used + m.raw > budget) {
                    OVER_BUDGET.incrementAndGet();
                    continue;
                }
                while (READY_BYTES.get() > READY_MAX) {
                    if (gen != generation) {
                        return;
                    }
                    Thread.sleep(20);
                }
                ByteBuffer data = readBody(m);
                if (data == null) {
                    // the writer may be replacing this very file (Windows refuses to open a file mid-move): retry once
                    Thread.sleep(100);
                    data = readBody(m);
                }
                if (data == null) {
                    continue;
                }
                READY.add(new Loaded(m.key, m.layer, m.x, m.y, m.z, data, m.geomTime, gen));
                READY_BYTES.addAndGet(data.capacity());
                used += m.raw;
                LOADED_FILES.incrementAndGet();
                LOADED_RAW.addAndGet(m.raw);
            }
            status = "loaded";
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            status = "load error";
            Capture.logError("disk.load", t);
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger READ_ERRORS_LOGGED = new java.util.concurrent.atomic.AtomicInteger();

    private static void readError(String what, File f, Throwable t) {
        ERRORS.incrementAndGet();
        if (READ_ERRORS_LOGGED.incrementAndGet() <= 20) {
            Capture.logError("disk." + what + " " + f, t != null ? t : new IllegalStateException("bad data"));
        }
    }

    private static Meta readHeader(File f) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 64))) {
            if (in.readInt() != MAGIC) {
                return null;
            }
            int version = in.readInt();
            if (version != 1 && version != 2) {
                return null;
            }
            int layer = in.readInt();
            int x = in.readInt();
            int y = in.readInt();
            int z = in.readInt();
            int vs = in.readInt();
            int raw = in.readInt();
            in.readLong();
            in.readLong();
            long geomTime = version >= 2 ? in.readLong() : 0L;
            int comp = in.readInt();
            if (vs != VS || layer < 0 || layer > 3 || raw <= 0 || raw % (VS * 4) != 0 || comp <= 0) {
                return null;
            }
            return new Meta(f, new BlockPos(x, y, z).func_177986_g(), layer, x, y, z, raw, comp, geomTime, version >= 2 ? 60 : 52);
        } catch (Throwable t) {
            readError("header", f, t);
            return null;
        }
    }

    private static long readHash(File f) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 64))) {
            in.skipBytes(4 * 8 + 8);
            return in.readLong();
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static ByteBuffer readBody(Meta m) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(m.file), 1 << 16))) {
            in.skipBytes(m.headerLen);
            byte[] comp = new byte[m.comp];
            in.readFully(comp);
            Inflater inf = new Inflater();
            byte[] raw = new byte[m.raw];
            try {
                inf.setInput(comp);
                int off = 0;
                while (off < raw.length && !inf.finished()) {
                    int n = inf.inflate(raw, off, raw.length - off);
                    if (n == 0 && (inf.needsInput() || inf.needsDictionary())) {
                        break;
                    }
                    off += n;
                }
                if (off != raw.length) {
                    readError("inflate " + off + "/" + raw.length, m.file, null);
                    return null;
                }
            } finally {
                inf.end();
            }
            ByteBuffer bb = ByteBuffer.allocateDirect(raw.length).order(ByteOrder.nativeOrder());
            bb.put(raw);
            bb.flip();
            return bb;
        } catch (Throwable t) {
            readError("body", m.file, t);
            return null;
        }
    }

    /** Main thread, from Far.render before drawing: GPU uploads of loaded sections, time-budgeted. */
    public static void pump(ViewFrustum frustum) {
        if (READY.isEmpty()) {
            return;
        }
        long deadline = System.nanoTime() + UPLOAD_BUDGET_NANOS;
        Loaded l;
        while ((l = READY.poll()) != null) {
            READY_BYTES.addAndGet(-l.data.capacity());
            if (l.gen != generation) {
                continue;
            }
            if (Far.outdated(l.key, l.geomTime)) {
                OUTDATED_SKIPPED.incrementAndGet();
                continue;
            }
            if (Far.acceptFromDisk(l.key, l.layer, l.x, l.y, l.z, frustum)) {
                Far.uploadLayer(l.key, l.x, l.y, l.z, l.layer, l.data, l.geomTime);
                uploadedFromDisk++;
            } else {
                rejectedFromDisk++;
            }
            if (System.nanoTime() > deadline) {
                break;
            }
        }
    }

    // ================= misc =================

    private static String serverId() {
        Minecraft mc = Minecraft.func_71410_x();
        String id;
        ServerData sd = mc.func_147104_D();
        if (sd != null && sd.field_78845_b != null) {
            id = sd.field_78845_b;
        } else if (mc.func_71401_C() != null) {
            id = "sp_" + mc.func_71401_C().func_71270_I();
        } else {
            id = "unknown";
        }
        return id.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public static String summary() {
        return "disk " + (ENABLED ? "ON" : "OFF") + " [" + status + (syncWorldId != null ? ", world " + syncWorldId + " synced to tick " + lastSync : "") + "]: on disk " + SCANNED.get() + " files ("
            + String.format("%.0f MB", SCANNED_COMP.get() / 1048576.0) + " at join), written " + WRITTEN_FILES.get() + " ("
            + String.format("%.0f MB -> %.0f MB", WRITTEN_RAW.get() / 1048576.0, WRITTEN_COMP.get() / 1048576.0)
            + "), deleted " + DELETED.get() + ", loaded " + LOADED_FILES.get() + " ("
            + String.format("%.0f MB", LOADED_RAW.get() / 1048576.0) + "), to GPU " + uploadedFromDisk + ", skipped "
            + rejectedFromDisk + ", over budget " + OVER_BUDGET.get() + ", backlog "
            + String.format("%.0f MB", BACKLOG.get() / 1048576.0) + ", uploads confirmed later " + heldCommitted + ", dropped as not owned " + heldDropped + ", invalidated by server " + INVALIDATED_FILES.get() + ", outdated skipped " + OUTDATED_SKIPPED.get() + ", errors " + ERRORS.get();
    }
}
