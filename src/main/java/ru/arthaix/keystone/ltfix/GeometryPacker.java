package ru.arthaix.keystone.ltfix;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * The tile geometry LittleTiles keeps for the next rebuild of a chunk (MixinBufferLink), about 100 KB per loaded tile
 * entity.
 *
 * 1. Heap, not direct memory. A kept copy used to be a direct buffer, and a direct buffer only returns its native memory
 *    when the collector finds it unreachable, which for buffers old enough to sit in the old generation waits for a
 *    marking cycle. Rebuilding a big import in view dropped buffers faster than that: direct memory reached its limit
 *    (32 GB) and the game crashed with "OutOfMemoryError: Direct buffer memory" after a 19 s stall in the JDK's own
 *    System.gc(). A new link now copies its bytes into a heap array (MixinBufferLink); the collector sees heap garbage
 *    and heap pressure as they are, and nothing is ever freed by hand. -Dltfix.pack=false keeps the old direct buffers.
 * 2. Packing. A link whose geometry has not been read for -Dltfix.packAfterMs (30 s) is deflated (level 1, about 5x on
 *    vertex data) by -Dltfix.packThreads (2) background threads; the next read inflates it again. Readers get identical
 *    bytes.
 * 3. A budget. Unpacked geometry is counted per link (a rebuild's new link inherits the count of the link it was made
 *    from, a link replaced or emptied in its tile entity gives its count back). Above -Dltfix.rawBudgetMB (an eighth of
 *    the heap) the packers stop waiting for due times and pack every link idle for -Dltfix.packPressureIdleMs (1000);
 *    above -Dltfix.rawHardMB (a fifth of the heap) chunk worker threads wait up to 2 s before merging more tiles.
 */
public final class GeometryPacker {
    public static final boolean ENABLED = !"false".equals(System.getProperty("ltfix.pack"));
    public static final int MIN_BYTES = 8192;
    /** 3 s packed and unpacked the same geometry over and over (LittleTiles rebuilds its sections every few seconds). */
    private static final long PACK_AFTER = Long.getLong("ltfix.packAfterMs", 30_000L) * 1_000_000L;
    private static final long PRESSURE_IDLE = Long.getLong("ltfix.packPressureIdleMs", 1_000L) * 1_000_000L;
    private static final int THREADS = Math.max(1, Integer.getInteger("ltfix.packThreads", 2));
    private static final long HEAP_MB = Runtime.getRuntime().maxMemory() >> 20;
    private static final long RAW_BUDGET = Long.getLong("ltfix.rawBudgetMB", Math.max(512L, HEAP_MB / 8)) << 20;
    private static final long RAW_HARD = Math.max(RAW_BUDGET, Long.getLong("ltfix.rawHardMB", Math.max(1024L, HEAP_MB / 5)) << 20);
    private static final long GC_AFTER_BYTES = Long.getLong("ltfix.packGcMB", 1024L) << 20;
    private static final long GC_INTERVAL_NANOS = 10_000_000_000L;
    private static final int CHUNK = 1 << 16;

    private static final class Tracked {
        final WeakReference<PackableLink> link;
        volatile long due;
        /** heap bytes of unpacked geometry this link is counted for */
        final AtomicLong raw = new AtomicLong();
        volatile boolean released;

        Tracked(PackableLink link, long due) {
            this.link = new WeakReference<PackableLink>(link);
            this.due = due;
        }
    }

    private static final ConcurrentLinkedQueue<Tracked> QUEUE = new ConcurrentLinkedQueue<Tracked>();
    private static final AtomicLong RAW = new AtomicLong();
    private static final AtomicLong RAW_PEAK = new AtomicLong();
    private static final AtomicLong HEAP_COPIES = new AtomicLong();
    private static final AtomicLong HEAP_COPY_BYTES = new AtomicLong();
    private static final AtomicLong PRESSURE_PACKS = new AtomicLong();
    private static final AtomicLong STALLS = new AtomicLong();
    private static final AtomicLong STALL_NANOS = new AtomicLong();
    private static final AtomicLong DIRECT_RETRIES = new AtomicLong();
    private static final AtomicLong PACKS = new AtomicLong();
    private static final AtomicLong PACK_RAW = new AtomicLong();
    private static final AtomicLong PACK_OUT = new AtomicLong();
    private static final AtomicLong PACK_NANOS = new AtomicLong();
    private static final AtomicLong UNPACKS = new AtomicLong();
    private static final AtomicLong UNPACK_RAW = new AtomicLong();
    private static final AtomicLong UNPACK_OUT = new AtomicLong();
    private static final AtomicLong UNPACK_NANOS = new AtomicLong();
    private static final AtomicLong FAILURES = new AtomicLong();
    private static final AtomicLong DROPPED_SINCE_GC = new AtomicLong();
    private static final AtomicLong GC_REQUESTS = new AtomicLong();
    private static final ThreadLocal<Inflater> INFLATER = ThreadLocal.withInitial(Inflater::new);
    /** the link a chunk worker just read in ChunkBlockLayerCache.add, and the array its bytes are in */
    private static final ThreadLocal<Object[]> INHERIT = new ThreadLocal<Object[]>();
    private static volatile boolean started;
    private static volatile boolean broken;
    private static volatile long lastGcRequest;
    private static volatile long lastShortageGc;
    private static Boolean concurrentExplicitGc;

    private GeometryPacker() {
    }

    public static boolean active() {
        return ENABLED && !broken;
    }

    /** A link of at least MIN_BYTES was created holding raw bytes of unpacked geometry (any thread); its token. */
    public static Object track(PackableLink link, long raw) {
        if (!ENABLED || broken) {
            return null;
        }
        if (!started) {
            start();
        }
        Tracked t = new Tracked(link, System.nanoTime() + PACK_AFTER);
        setRaw(t, raw);
        QUEUE.add(t);
        return t;
    }

    /** The number of unpacked heap bytes a link holds now (0 once packed). */
    public static void setRaw(Object token, long raw) {
        if (!(token instanceof Tracked)) {
            return;
        }
        Tracked t = (Tracked) token;
        if (t.released && raw > 0) {
            return;
        }
        long now = RAW.addAndGet(raw - t.raw.getAndSet(raw));
        long peak;
        while (now > (peak = RAW_PEAK.get()) && !RAW_PEAK.compareAndSet(peak, now)) {
        }
    }

    /** The link is no longer its tile entity's geometry (replaced or emptied): it is not counted or packed any more. */
    public static void release(Object token) {
        if (token instanceof Tracked) {
            Tracked t = (Tracked) token;
            setRaw(t, 0L);
            t.released = true;
        }
    }

    /** ChunkBlockLayerCache.add read this link's buffer; a link made from it next on this thread takes over its count. */
    public static void offerInheritance(PackableLink from, ByteBuffer buffer) {
        if (buffer != null && buffer.hasArray()) {
            INHERIT.set(new Object[] {from, buffer.array()});
        } else {
            INHERIT.remove();
        }
    }

    /** A new link with this buffer: the link it shares its bytes with, if that was the one just read on this thread. */
    public static PackableLink takeInheritance(ByteBuffer buffer) {
        Object[] offer = INHERIT.get();
        if (offer == null) {
            return null;
        }
        INHERIT.remove();
        return buffer.hasArray() && buffer.array() == offer[1] ? (PackableLink) offer[0] : null;
    }

    public static void inherit(Object fromToken, Object toToken) {
        if (fromToken instanceof Tracked && toToken instanceof Tracked) {
            Tracked from = (Tracked) fromToken;
            Tracked to = (Tracked) toToken;
            long raw = from.raw.getAndSet(0L);
            from.released = true;
            if (raw > 0) {
                to.raw.addAndGet(raw);
            }
        }
    }

    /** Bytes 0..length of a direct buffer as a native-order heap buffer (position 0, limit length); else src itself. */
    public static ByteBuffer heapCopy(ByteBuffer src, int length) {
        if (src == null || !src.isDirect() || length < 0 || src.capacity() < length) {
            return src;
        }
        try {
            ByteBuffer view = src.duplicate();
            view.clear();
            view.limit(length);
            byte[] bytes = new byte[length];
            view.get(bytes);
            HEAP_COPIES.incrementAndGet();
            HEAP_COPY_BYTES.addAndGet(length);
            return ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
        } catch (OutOfMemoryError e) {
            return src;
        }
    }

    /** Chunk worker, before merging a section's tiles: while unpacked geometry is over the hard limit, wait up to 2 s. */
    public static void waitForRoom() {
        if (!ENABLED || broken || RAW.get() <= RAW_HARD) {
            return;
        }
        long start = System.nanoTime();
        long deadline = start + 2_000_000_000L;
        STALLS.incrementAndGet();
        try {
            while (RAW.get() > RAW_HARD && System.nanoTime() < deadline) {
                Thread.sleep(25L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        STALL_NANOS.addAndGet(System.nanoTime() - start);
    }

    /** A direct allocation for a chunk failed: ask for a concurrent collection (at most every 5 s) and wait a moment. */
    public static void onDirectShortage() {
        DIRECT_RETRIES.incrementAndGet();
        long now = System.nanoTime();
        if (now - lastShortageGc > 5_000_000_000L && concurrentExplicitGc()) {
            lastShortageGc = now;
            GC_REQUESTS.incrementAndGet();
            System.gc();
        }
        try {
            Thread.sleep(250L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static synchronized void start() {
        if (started) {
            return;
        }
        for (int i = 0; i < THREADS; i++) {
            Thread t = new Thread(GeometryPacker::run, "ltfix geometry packer " + i);
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        }
        started = true;
    }

    private static void run() {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        byte[] in = new byte[CHUNK];
        byte[] out = new byte[CHUNK];
        int requeued = 0;
        while (true) {
            try {
                Tracked t = QUEUE.poll();
                if (t == null) {
                    requestCollectionIfDue();
                    Thread.sleep(250L);
                    continue;
                }
                PackableLink link = t.link.get();
                if (link == null || t.released) {
                    // collected, or no longer its tile entity's geometry: whatever it was counted for is gone
                    setRaw(t, 0L);
                    continue;
                }
                long now = System.nanoTime();
                boolean pressure = RAW.get() > RAW_BUDGET;
                if (!pressure && t.due > now) {
                    QUEUE.add(t);
                    requestCollectionIfDue();
                    Thread.sleep(Math.min(250L, Math.max(10L, (t.due - now) / 1_000_000L)));
                    continue;
                }
                long touched = link.ltfix$touched();
                if (now - touched < (pressure ? PRESSURE_IDLE : PACK_AFTER)) {
                    t.due = touched + PACK_AFTER;
                    QUEUE.add(t);
                    if (pressure && ++requeued > QUEUE.size() + 16) {
                        // every link left is in use right now
                        requeued = 0;
                        Thread.sleep(20L);
                    }
                    continue;
                }
                requeued = 0;
                if (link.ltfix$pack(deflater, in, out) && pressure) {
                    PRESSURE_PACKS.incrementAndGet();
                }
                requestCollectionIfDue();
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                FAILURES.incrementAndGet();
            }
        }
    }

    /**
     * Chunk builders and LittleTiles' own render buffers are still direct memory, freed by the collector. Above
     * -Dltfix.directGcMB (6144) of direct memory a concurrent cycle is asked for every 20 s at most.
     */
    private static final long DIRECT_GC_BYTES = Long.getLong("ltfix.directGcMB", 6144L) << 20;
    private static final long DIRECT_GC_INTERVAL_NANOS = 20_000_000_000L;
    private static java.lang.management.BufferPoolMXBean directPool;

    private static long directUsed() {
        java.lang.management.BufferPoolMXBean pool = directPool;
        if (pool == null) {
            for (java.lang.management.BufferPoolMXBean b : ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class)) {
                if ("direct".equals(b.getName())) {
                    pool = b;
                }
            }
            if (pool == null) {
                return 0L;
            }
            directPool = pool;
        }
        return pool.getMemoryUsed();
    }

    private static void requestCollectionIfDue() {
        long now = System.nanoTime();
        boolean dropped = DROPPED_SINCE_GC.get() >= GC_AFTER_BYTES;
        long sinceLast = now - lastGcRequest;
        boolean dueForDropped = dropped && sinceLast >= GC_INTERVAL_NANOS;
        boolean dueForDirect = sinceLast >= DIRECT_GC_INTERVAL_NANOS && directUsed() >= DIRECT_GC_BYTES;
        if ((!dueForDropped && !dueForDirect) || !concurrentExplicitGc()) {
            return;
        }
        lastGcRequest = now;
        DROPPED_SINCE_GC.set(0L);
        GC_REQUESTS.incrementAndGet();
        System.gc();
    }

    /** True only when System.gc() starts a concurrent cycle instead of a stop-the-world full collection. */
    private static synchronized boolean concurrentExplicitGc() {
        if (concurrentExplicitGc == null) {
            boolean ok = false;
            try {
                Class<?> beanClass = Class.forName("com.sun.management.HotSpotDiagnosticMXBean");
                Object bean = ManagementFactory.getPlatformMXBean((Class) beanClass);
                Object option = beanClass.getMethod("getVMOption", String.class).invoke(bean, "ExplicitGCInvokesConcurrent");
                Object disabled = beanClass.getMethod("getVMOption", String.class).invoke(bean, "DisableExplicitGC");
                boolean concurrent = "true".equals(option.getClass().getMethod("getValue").invoke(option));
                boolean off = "true".equals(disabled.getClass().getMethod("getValue").invoke(disabled));
                ok = concurrent && !off;
                for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                    if (gc.getName().contains("Shenandoah") && !off) {
                        ok = true;
                    }
                }
            } catch (Throwable t) {
                ok = false;
            }
            concurrentExplicitGc = ok;
        }
        return concurrentExplicitGc;
    }

    /** Deflates bytes 0..length of src (a private view); null when it would not save at least a sixteenth. */
    public static byte[] deflate(ByteBuffer src, int length, Deflater d, byte[] in, byte[] out) {
        d.reset();
        src.clear();
        if (src.capacity() < length) {
            return null;
        }
        src.limit(length);
        int budget = length - (length >> 4);
        byte[] acc = new byte[Math.max(1024, length / 4)];
        int size = 0;
        boolean finishing = false;
        while (!d.finished()) {
            if (!finishing && d.needsInput()) {
                int n = Math.min(in.length, src.remaining());
                if (n > 0) {
                    src.get(in, 0, n);
                    d.setInput(in, 0, n);
                }
                if (!src.hasRemaining()) {
                    d.finish();
                    finishing = true;
                }
            }
            int n = d.deflate(out, 0, out.length);
            if (n > 0) {
                if (size + n > budget) {
                    return null;
                }
                if (size + n > acc.length) {
                    acc = Arrays.copyOf(acc, Math.min(budget, Math.max(acc.length * 2, size + n)));
                }
                System.arraycopy(out, 0, acc, size, n);
                size += n;
            }
        }
        return Arrays.copyOf(acc, size);
    }

    /** A link now holds its geometry packed; its unpacked bytes were dropped. */
    public static void onPacked(Object token, int raw, int packed, long nanos) {
        setRaw(token, 0L);
        PACKS.incrementAndGet();
        PACK_RAW.addAndGet(raw);
        PACK_OUT.addAndGet(packed);
        PACK_NANOS.addAndGet(nanos);
        DROPPED_SINCE_GC.addAndGet(raw);
    }

    /** Inflates a packed link into a new native-order heap buffer, position 0 and limit length. */
    public static ByteBuffer inflate(byte[] packed, int length) {
        long t0 = System.nanoTime();
        Inflater inf = INFLATER.get();
        inf.reset();
        inf.setInput(packed);
        byte[] bytes = new byte[length];
        int filled = 0;
        try {
            while (filled < length) {
                int n = inf.inflate(bytes, filled, length - filled);
                if (n == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) {
                    break;
                }
                filled += n;
            }
        } catch (DataFormatException e) {
            broken = true;
            FAILURES.incrementAndGet();
            throw new IllegalStateException("ltfix: packed tile geometry is corrupt", e);
        }
        if (filled < length) {
            broken = true;
            FAILURES.incrementAndGet();
            throw new IllegalStateException("ltfix: packed tile geometry is short: " + filled + " of " + length);
        }
        UNPACKS.incrementAndGet();
        UNPACK_RAW.addAndGet(length);
        UNPACK_OUT.addAndGet(packed.length);
        UNPACK_NANOS.addAndGet(System.nanoTime() - t0);
        return ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
    }

    public static String stats() {
        if (!ENABLED) {
            return "pack off";
        }
        long held = (PACK_RAW.get() - UNPACK_RAW.get()) >> 20;
        long heldPacked = (PACK_OUT.get() - UNPACK_OUT.get()) >> 20;
        return "pack " + PACKS.get() + " " + held + "MB->" + heldPacked + "MB " + (PACK_NANOS.get() / 1_000_000L) + "ms unpack " + UNPACKS.get()
            + " " + (UNPACK_RAW.get() >> 20) + "MB " + (UNPACK_NANOS.get() / 1_000_000L) + "ms queue " + QUEUE.size() + " gc " + GC_REQUESTS.get()
            + " raw " + (RAW.get() >> 20) + "/" + (RAW_BUDGET >> 20) + "MB peak " + (RAW_PEAK.get() >> 20) + "MB heapCopies " + HEAP_COPIES.get()
            + " " + (HEAP_COPY_BYTES.get() >> 20) + "MB pressurePacks " + PRESSURE_PACKS.get() + " stalls " + STALLS.get() + " "
            + (STALL_NANOS.get() / 1_000_000L) + "ms directRetries " + DIRECT_RETRIES.get()
            + (FAILURES.get() > 0 ? " FAILURES " + FAILURES.get() : "") + (broken ? " BROKEN" : "");
    }
}
