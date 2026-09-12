package ru.arthaix.keystone.ltfix;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;

/**
 * JourneyMap decodes a region map PNG on the client thread whenever the minimap needs a region it does not hold:
 * 30-80 ms each, several at once after a teleport or when a flight crosses a region border. Regions around the player
 * are decoded ahead on a background thread, from the folders JourneyMap has read from (one per map type); when
 * JourneyMap then reads such a file and it has not changed since, it gets the decoded image at once. A decode still
 * running is never waited for.
 */
public final class RegionPrewarm {
    static final boolean ENABLED = !"false".equals(System.getProperty("ltfix.jmPrewarm"));
    private static final int RADIUS = 1;
    private static final int KEEP_RADIUS = 2;
    private static final int MAX_DIRS = 6;
    private static final long PERIOD_NANOS = 500_000_000L;

    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ltfix journeymap prewarm");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final ConcurrentHashMap<File, Future<Decoded>> CACHE = new ConcurrentHashMap<File, Future<Decoded>>();
    /** region image folders JourneyMap read from, most recent last */
    private static final Map<File, Boolean> DIRS = new LinkedHashMap<File, Boolean>(8, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<File, Boolean> eldest) {
            return size() > MAX_DIRS;
        }
    };
    private static final AtomicBoolean SCHEDULED = new AtomicBoolean();
    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong MISSES = new AtomicLong();
    private static long nextNanos;

    private static final class Decoded {
        final long modified;
        final long length;
        final BufferedImage image;

        Decoded(long modified, long length, BufferedImage image) {
            this.modified = modified;
            this.length = length;
            this.image = image;
        }
    }

    private RegionPrewarm() {
    }

    /** Any JourneyMap thread, instead of ImageIO.read(file) in RegionImageHandler.readRegionImage. */
    public static BufferedImage read(File file) throws IOException {
        if (!ENABLED) {
            return ImageIO.read(file);
        }
        File dir = file.getParentFile();
        if (dir != null) {
            synchronized (DIRS) {
                DIRS.put(dir, Boolean.TRUE);
            }
        }
        Future<Decoded> f = CACHE.remove(file);
        if (f != null) {
            if (f.isDone() && !f.isCancelled()) {
                try {
                    Decoded d = f.get();
                    if (d != null && d.image != null && d.modified == file.lastModified() && d.length == file.length()) {
                        HITS.incrementAndGet();
                        return d.image;
                    }
                } catch (Exception ignored) {
                    // decode failed: read it here like JourneyMap would
                }
            } else {
                f.cancel(false);
            }
        }
        MISSES.incrementAndGet();
        return ImageIO.read(file);
    }

    /** Client thread, every frame: at most every 500 ms hands the player's region to the prewarm thread. */
    public static void tick() {
        long now = System.nanoTime();
        if (!ENABLED || now < nextNanos) {
            return;
        }
        nextNanos = now + PERIOD_NANOS;
        Entity view = Minecraft.func_71410_x().func_175606_aa();
        if (view == null || !SCHEDULED.compareAndSet(false, true)) {
            return;
        }
        final int rx = (int) Math.floor(view.field_70165_t / 512.0);
        final int rz = (int) Math.floor(view.field_70161_v / 512.0);
        DECODER.execute(() -> {
            try {
                schedule(rx, rz);
            } finally {
                SCHEDULED.set(false);
            }
        });
    }

    /** Prewarm thread: evict far regions, queue decodes of near ones that exist on disk. */
    private static void schedule(int rx, int rz) {
        for (Iterator<Map.Entry<File, Future<Decoded>>> it = CACHE.entrySet().iterator(); it.hasNext();) {
            Map.Entry<File, Future<Decoded>> e = it.next();
            int[] r = region(e.getKey());
            if (r == null || Math.max(Math.abs(r[0] - rx), Math.abs(r[1] - rz)) > KEEP_RADIUS) {
                e.getValue().cancel(false);
                it.remove();
            }
        }
        List<File> dirs;
        synchronized (DIRS) {
            dirs = new ArrayList<File>(DIRS.keySet());
        }
        for (File dir : dirs) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    final File f = new File(dir, (rx + dx) + "," + (rz + dz) + ".png");
                    if (!CACHE.containsKey(f) && f.isFile()) {
                        CACHE.put(f, DECODER.submit(() -> {
                            long modified = f.lastModified();
                            long length = f.length();
                            return new Decoded(modified, length, ImageIO.read(f));
                        }));
                    }
                }
            }
        }
    }

    private static int[] region(File f) {
        String name = f.getName();
        int comma = name.indexOf(',');
        int dot = name.lastIndexOf(".png");
        if (comma <= 0 || dot <= comma) {
            return null;
        }
        try {
            return new int[] { Integer.parseInt(name.substring(0, comma)), Integer.parseInt(name.substring(comma + 1, dot)) };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String stats() {
        return "jmPrewarm " + HITS.get() + "/" + (HITS.get() + MISSES.get());
    }
}
