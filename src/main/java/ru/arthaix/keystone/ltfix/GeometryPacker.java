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
 * The tile geometry LittleTiles keeps for the next rebuild of a chunk (MixinBufferLink) sat in direct memory: about
 * 100 KB per loaded tile entity, 10 GB after a flight over the city, which pushed the whole machine into the page file.
 * A link whose geometry has not been read for -Dltfix.packAfterMs (3 s) is deflated (level 1, about 5x on vertex data)
 * into a heap array by -Dltfix.packThreads (2) background threads and its direct buffer is dropped. The next read (a
 * chunk worker merging the section, or LittleTiles combining structures) inflates it into a new direct buffer.
 * The bytes a reader gets are identical; -Dltfix.pack=false keeps everything raw.
 *
 * A dropped direct buffer only returns its native memory when the collector finds it unreachable, which on a large old
 * generation can take minutes. After -Dltfix.packGcMB (1024) of dropped geometry the packer asks for a collection with
 * System.gc(), but only where that starts a concurrent cycle (-XX:+ExplicitGCInvokesConcurrent, or Shenandoah) and at
 * most every 10 s; otherwise it never calls it.
 */
public final class GeometryPacker {
    public static final boolean ENABLED = !"false".equals(System.getProperty("ltfix.pack"));
    public static final int MIN_BYTES = 8192;
    /** 3 s packed and unpacked the same geometry over and over (LittleTiles rebuilds its sections every few seconds). */
    private static final long PACK_AFTER = Long.getLong("ltfix.packAfterMs", 30_000L) * 1_000_000L;
    private static final int THREADS = Math.max(1, Integer.getInteger("ltfix.packThreads", 2));
    private static final long GC_AFTER_BYTES = Long.getLong("ltfix.packGcMB", 1024L) << 20;
    private static final long GC_INTERVAL_NANOS = 10_000_000_000L;
    private static final int CHUNK = 1 << 16;

    private static final class Tracked {
        final WeakReference<PackableLink> link;
        final long due;

        Tracked(PackableLink link, long due) {
            this.link = new WeakReference<PackableLink>(link);
            this.due = due;
        }
    }

    private static final ConcurrentLinkedQueue<Tracked> QUEUE = new ConcurrentLinkedQueue<Tracked>();
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
    private static final ThreadLocal<byte[]> SCRATCH = ThreadLocal.withInitial(() -> new byte[CHUNK]);
    private static volatile boolean started;
    private static volatile boolean broken;
    private static volatile long lastGcRequest;
    private static Boolean concurrentExplicitGc;

    private GeometryPacker() {
    }

    /** A link was created (any thread). */
    public static void track(PackableLink link) {
        if (!ENABLED || broken) {
            return;
        }
        if (!started) {
            start();
        }
        QUEUE.add(new Tracked(link, System.nanoTime() + PACK_AFTER));
    }

    public static boolean active() {
        return ENABLED && !broken;
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
        while (true) {
            try {
                Tracked t = QUEUE.poll();
                if (t == null) {
                    requestCollectionIfDue();
                    Thread.sleep(500L);
                    continue;
                }
                long now = System.nanoTime();
                if (t.due > now) {
                    QUEUE.add(t);
                    requestCollectionIfDue();
                    Thread.sleep(Math.min(250L, Math.max(10L, (t.due - now) / 1_000_000L)));
                    continue;
                }
                PackableLink link = t.link.get();
                if (link == null) {
                    continue;
                }
                long touched = link.ltfix$touched();
                if (now - touched < PACK_AFTER) {
                    QUEUE.add(new Tracked(link, touched + PACK_AFTER));
                    continue;
                }
                link.ltfix$pack(deflater, in, out);
                requestCollectionIfDue();
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                FAILURES.incrementAndGet();
            }
        }
    }

    /**
     * Geometry of chunks the player left is garbage at once, but its native memory only comes back when a collection
     * finds the buffers unreachable; they sit in the old generation, so that waited for the next marking cycle and direct
     * memory reached 14.8 GB in a flight. Above -Dltfix.directGcMB (6144) of direct memory a concurrent cycle is asked
     * for every 20 s at most.
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

    /** A link now holds its geometry packed; its direct buffer was dropped. */
    public static void onPacked(int raw, int packed, long nanos) {
        PACKS.incrementAndGet();
        PACK_RAW.addAndGet(raw);
        PACK_OUT.addAndGet(packed);
        PACK_NANOS.addAndGet(nanos);
        DROPPED_SINCE_GC.addAndGet(raw);
    }

    /** Inflates a packed link into a new native-order direct buffer, position 0 and limit length. */
    public static ByteBuffer inflate(byte[] packed, int length) {
        long t0 = System.nanoTime();
        Inflater inf = INFLATER.get();
        byte[] scratch = SCRATCH.get();
        inf.reset();
        inf.setInput(packed);
        ByteBuffer dst = ByteBuffer.allocateDirect(length).order(ByteOrder.nativeOrder());
        try {
            while (dst.hasRemaining()) {
                int n = inf.inflate(scratch, 0, Math.min(scratch.length, dst.remaining()));
                if (n == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) {
                    break;
                }
                dst.put(scratch, 0, n);
            }
        } catch (DataFormatException e) {
            broken = true;
            FAILURES.incrementAndGet();
            throw new IllegalStateException("ltfix: packed tile geometry is corrupt", e);
        }
        if (dst.hasRemaining()) {
            broken = true;
            FAILURES.incrementAndGet();
            throw new IllegalStateException("ltfix: packed tile geometry is short: " + dst.position() + " of " + length);
        }
        dst.flip();
        UNPACKS.incrementAndGet();
        UNPACK_RAW.addAndGet(length);
        UNPACK_OUT.addAndGet(packed.length);
        UNPACK_NANOS.addAndGet(System.nanoTime() - t0);
        return dst;
    }

    public static String stats() {
        if (!ENABLED) {
            return "pack off";
        }
        long held = (PACK_RAW.get() - UNPACK_RAW.get()) >> 20;
        long heldPacked = (PACK_OUT.get() - UNPACK_OUT.get()) >> 20;
        return "pack " + PACKS.get() + " " + held + "MB->" + heldPacked + "MB " + (PACK_NANOS.get() / 1_000_000L) + "ms unpack " + UNPACKS.get()
            + " " + (UNPACK_RAW.get() >> 20) + "MB " + (UNPACK_NANOS.get() / 1_000_000L) + "ms queue " + QUEUE.size() + " gc " + GC_REQUESTS.get()
            + (FAILURES.get() > 0 ? " FAILURES " + FAILURES.get() : "") + (broken ? " BROKEN" : "");
    }
}
