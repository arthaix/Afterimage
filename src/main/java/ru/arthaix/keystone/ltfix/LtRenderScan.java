package ru.arthaix.keystone.ltfix;

import java.util.IdentityHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.creativemd.littletiles.client.render.cache.IRenderDataCache;
import com.creativemd.littletiles.client.render.cache.LayeredRenderBufferCache;
import com.creativemd.littletiles.common.tileentity.TileEntityLittleTiles;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import ru.arthaix.keystone.ltfix.mixin.RenderManagerAccessor;

/**
 * Client metric: LittleTiles tile entities inside the render distance that have tiles but no geometry at all, i.e. that
 * are invisible right now. The client thread only copies the tile entity list; a low-priority thread does the counting
 * (racy reads, good enough for a counter).
 */
public final class LtRenderScan {
    private static final long STUCK_NANOS = 30_000_000_000L;
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ltfix render scan");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static volatile String last = "ltTiles n/a";
    /** scan thread only: tile entity -> first scan that saw it without geometry */
    private static IdentityHashMap<Object, Long> missingSince = new IdentityHashMap<Object, Long>();

    private LtRenderScan() {
    }

    /** Client thread. */
    public static void request() {
        Minecraft mc = Minecraft.func_71410_x();
        WorldClient w = mc.field_71441_e;
        Entity view = mc.func_175606_aa();
        if (w == null || view == null || !RUNNING.compareAndSet(false, true)) {
            return;
        }
        // chunks the server should have sent that the client does not have: a sending bug shows as a count that stays up
        int cx = ((int) Math.floor(view.field_70165_t)) >> 4;
        int cz = ((int) Math.floor(view.field_70161_v)) >> 4;
        int missing = 0;
        for (int dx = -VIEW_CHUNKS; dx <= VIEW_CHUNKS; dx++) {
            for (int dz = -VIEW_CHUNKS; dz <= VIEW_CHUNKS; dz++) {
                net.minecraft.world.chunk.Chunk c = w.func_72863_F().func_186026_b(cx + dx, cz + dz);
                if (c == null || c.func_76621_g()) {
                    missing++;
                }
            }
        }
        missingChunks = missing;
        final Object[] tes;
        try {
            tes = w.field_147482_g.toArray();
        } catch (Throwable t) {
            RUNNING.set(false);
            return;
        }
        final double px = view.field_70165_t;
        final double pz = view.field_70161_v;
        final double r = Math.max(1, mc.field_71474_y.field_151451_c - 1) * 16.0;
        EXEC.execute(() -> {
            try {
                scan(tes, px, pz, r);
            } catch (Throwable t) {
                last = "ltTiles err " + t;
            } finally {
                RUNNING.set(false);
            }
        });
    }

    private static void scan(Object[] tes, double px, double pz, double r) {
        long now = System.nanoTime();
        IdentityHashMap<Object, Long> next = new IdentityHashMap<Object, Long>();
        int total = 0;
        int notLoaded = 0;
        int noGeom = 0;
        int queued = 0;
        int neverRendered = 0;
        int stuck = 0;
        StringBuilder stuckInfo = new StringBuilder();
        for (Object o : tes) {
            if (!(o instanceof TileEntityLittleTiles)) {
                continue;
            }
            TileEntityLittleTiles te = (TileEntityLittleTiles) o;
            BlockPos p = te.func_174877_v();
            if (Math.abs(p.func_177958_n() + 0.5 - px) > r || Math.abs(p.func_177952_p() + 0.5 - pz) > r) {
                continue;
            }
            total++;
            if (te.render == null || !te.hasLoaded()) {
                notLoaded++;
                continue;
            }
            if (te.isEmpty()) {
                continue;
            }
            LayeredRenderBufferCache cache = te.render.getBufferCache();
            boolean any = false;
            for (int i = 0; i < 4 && !any; i++) {
                IRenderDataCache d = cache.get(i);
                // a packed link must not be unpacked just to be counted
                any = d != null && (d instanceof PackableLink ? ((PackableLink) d).ltfix$hasGeometry() : d.byteBuffer() != null);
            }
            if (any) {
                continue;
            }
            noGeom++;
            Long since = missingSince.get(te);
            next.put(te, since == null ? now : since);
            if (te.render.isInQueue()) {
                queued++;
            } else {
                if (((RenderManagerAccessor) te.render).ltfix$finishedIndex() < 0) {
                    neverRendered++;
                }
                if (since != null && now - since > STUCK_NANOS) {
                    stuck++;
                    if (stuckInfo.length() < 400) {
                        stuckInfo.append(describe(te)).append("; ");
                    }
                }
            }
        }
        missingSince = next;
        last = "ltTiles " + total + " notLoaded " + notLoaded + " noGeom " + noGeom + " queued " + queued + " neverRendered "
            + neverRendered + " stuck30s " + stuck + " remarked " + RenderChunks.remarked()
            + (stuckInfo.length() > 0 ? " stuckAt [" + stuckInfo + "]" : "");
    }

    /** Position, tile count and how many of its tiles are invisible, for a tile entity without geometry. */
    private static String describe(TileEntityLittleTiles te) {
        BlockPos p = te.func_174877_v();
        StringBuilder sb = new StringBuilder();
        sb.append(p.func_177958_n()).append(',').append(p.func_177956_o()).append(',').append(p.func_177952_p());
        try {
            int invisible = 0;
            int all = 0;
            for (com.creativemd.creativecore.common.utils.type.Pair<?, com.creativemd.littletiles.common.tile.LittleTile> pair : te.allTiles()) {
                all++;
                if (pair.value.invisible) {
                    invisible++;
                }
            }
            sb.append(" tiles ").append(all).append(" invisible ").append(invisible).append(" rendered ").append(te.isRendered());
        } catch (Throwable t) {
            sb.append(" ?");
        }
        return sb.toString();
    }

    /** View distance of the server in chunks (server.properties view-distance). */
    private static final int VIEW_CHUNKS = Integer.getInteger("ltfix.viewChunks", 6);
    private static volatile int missingChunks;

    public static String last() {
        return last + " missingChunks " + missingChunks;
    }
}
