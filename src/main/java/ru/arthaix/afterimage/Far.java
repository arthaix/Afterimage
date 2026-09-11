package ru.arthaix.afterimage;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.ViewFrustum;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.MobEffects;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GLContext;
import ru.arthaix.afterimage.mixin.ViewFrustumAccessor;

/**
 * Afterimage phase 1: far zone.
 *
 * Section geometry is copied GPU-side (glCopyBufferSubData, no CPU readback) at the moment vanilla would
 * lose it: a RenderChunk moving to another position, renderers being deleted, or a section being recompiled
 * while its chunk is no longer loaded on the client. The copies are drawn before the vanilla SOLID layer with
 * the clipping planes pushed out and fog pushed out, then the depth buffer is cleared, so vanilla terrain (always
 * nearer) draws on top. A copy stays while vanilla shows the section and is only replaced when the section's
 * geometry changed in the meantime (upload fingerprints), so flying back and forth costs no copies.
 * Bytes are identical to what vanilla uploaded (verified in phase 0). SOLID, CUTOUT_MIPPED and CUTOUT only;
 * translucent stays vanilla-only in this phase.
 */
public final class Far {
    public static volatile boolean ENABLED = !"false".equals(System.getProperty("afterimage.far"));
    private static final long BUDGET = Long.getLong("afterimage.farBudgetMB", 8192L) << 20;
    private static final double FAR_PLANE = Integer.getInteger("afterimage.farPlane", 8192);
    /** Near plane of the far pass. Much larger than vanilla's so a 24-bit depth buffer resolves microblocks kilometres away. */
    private static final double FAR_NEAR = Double.parseDouble(System.getProperty("afterimage.farNear", "6"));
    /** Normal air fog is pushed out to this distance while the far zone has content; 0 keeps vanilla fog. */
    public static volatile int fogEnd = Integer.getInteger("afterimage.fogEnd", 2048);
    /**
     * A copy keeps being drawn under a freshly compiled vanilla section until the two match or vanilla has not changed the
     * section for this long. Vanilla compiles a section as soon as the chunk arrives, before its tile entities (LittleTiles,
     * Chisels & Bits) are applied, so an immediate handover made buildings vanish and come back while chunks loaded.
     */
    private static final long SETTLE_NANOS = Long.getLong("afterimage.settleMs", 3000L) * 1_000_000L;
    private static final int LAYERS = 3;
    private static final int VS = 28;

    private static final class Entry {
        final long key;
        final int x;
        final int y;
        final int z;
        final int[] ids = new int[LAYERS];
        final int[] counts = new int[LAYERS];
        /** Multiset fingerprint per layer of the copied bytes, 0 when unknown. */
        final long[] hash = new long[LAYERS];
        /** Set when the section got different geometry after the copy was made. */
        boolean stale;
        /** Fingerprint per layer of what vanilla currently has for this section, 0 = empty or not seen. */
        final long[] vanillaHash = new long[LAYERS];
        /** System.nanoTime() of vanilla's last compile of this section, 0 = none seen while the copy existed. */
        long vanillaChanged;
        /** Vanilla has taken over this section; stays set until the section leaves vanilla again. */
        boolean handedOver;
        long bytes;
        double dist2;

        Entry(long key, BlockPos p) {
            this(key, p.func_177958_n(), p.func_177956_o(), p.func_177952_p());
        }

        Entry(long key, int x, int y, int z) {
            this.key = key;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final HashMap<Long, Entry> ENTRIES = new HashMap<Long, Entry>(8192);
    private static final ArrayList<Entry> VISIBLE = new ArrayList<Entry>(8192);
    private static final FloatBuffer MAT = BufferUtils.createFloatBuffer(16);
    private static final float[] PROJ = new float[16];
    private static final float[] MV = new float[16];
    private static final float[] CLIP = new float[16];
    private static final float[][] PLANES = new float[6][4];

    private static Boolean supported;
    private static long bytes;
    private static long captures;
    private static long reused;
    private static long settling;
    private static long drops;
    private static long evictions;
    private static long errors;
    private static int lastDrawn;
    private static int lastSkippedVanilla;
    private static int lastCulled;
    private static double camX;
    private static double camY;
    private static double camZ;

    private Far() {
    }

    // ================= capture =================

    /** RenderChunk is about to move to another position, or its GL resources are about to be deleted. */
    public static void onLeave(RenderChunk rc) {
        if (!ENABLED || !Capture.onMainThread()) {
            return;
        }
        try {
            capture(rc);
        } catch (Throwable t) {
            fail("onLeave", t);
        }
    }

    /** RenderChunk is about to get a new CompiledChunk. */
    public static void onCompiledReplace(RenderChunk rc, CompiledChunk next) {
        if (!ENABLED || !Capture.onMainThread()) {
            return;
        }
        try {
            BlockPos p = rc.func_178568_j();
            if (chunkLoaded(p)) {
                if (next != CompiledChunk.field_178502_a) {
                    long key = p.func_177986_g();
                    Entry e = ENTRIES.get(key);
                    if (e != null) {
                        BlockRenderLayer[] layers = BlockRenderLayer.values();
                        for (int l = 0; l < LAYERS; l++) {
                            if (next.func_178491_b(layers[l])) {
                                e.vanillaHash[l] = 0L;
                                if (e.ids[l] > 0) {
                                    e.stale = true;
                                }
                            }
                        }
                        e.vanillaChanged = System.nanoTime();
                    }
                    Disk.onVanillaCompiled(key, next);
                }
            } else {
                capture(rc);
            }
        } catch (Throwable t) {
            fail("onCompiledReplace", t);
        }
    }

    /** Main thread, from Capture.onUpload: a section layer (0..2) got geometry with this fingerprint. */
    public static void onUpload(long key, int layer, long ms) {
        Entry e = ENTRIES.get(key);
        if (e != null) {
            e.vanillaHash[layer] = ms;
            if (e.hash[layer] != ms) {
                e.stale = true;
            }
        }
    }

    private static boolean supported() {
        if (supported == null) {
            supported = GLContext.getCapabilities().OpenGL31;
        }
        return supported;
    }

    private static boolean chunkLoaded(BlockPos p) {
        WorldClient w = Minecraft.func_71410_x().field_71441_e;
        if (w == null) {
            return false;
        }
        Chunk c = w.func_72863_F().func_186026_b(p.func_177958_n() >> 4, p.func_177952_p() >> 4);
        return c != null && !c.func_76621_g();
    }

    private static void capture(RenderChunk rc) {
        if (!supported()) {
            return;
        }
        CompiledChunk cc = rc.func_178571_g();
        if (cc == null || cc == CompiledChunk.field_178502_a) {
            return;
        }
        BlockPos p = rc.func_178568_j();
        long key = p.func_177986_g();
        Entry old = ENTRIES.get(key);
        boolean tracked = Capture.ENABLED;
        if (old != null && !old.stale && tracked) {
            // The copy already holds exactly this geometry: any later upload would have marked it stale.
            old.handedOver = false;
            old.vanillaChanged = 0L;
            reused++;
            return;
        }
        BlockRenderLayer[] layers = BlockRenderLayer.values();
        int[] ids = new int[LAYERS];
        int[] counts = new int[LAYERS];
        long[] hashes = new long[LAYERS];
        long total = 0;
        boolean any = false;
        boolean unknown = !tracked;
        for (int l = 0; l < LAYERS; l++) {
            if (cc.func_178491_b(layers[l])) {
                continue;
            }
            VertexBuffer vb = rc.func_178565_b(l);
            if (vb == null) {
                continue;
            }
            IAfterimageVbo v = (IAfterimageVbo) (Object) vb;
            int src = v.afterimage$glId();
            if (src <= 0 || v.afterimage$vertexSize() != VS) {
                continue;
            }
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, src);
            int size;
            if (tracked) {
                // size of the last upload, recorded by the upload hook; glGetBufferParameter would stall the CPU on the driver
                size = v.afterimage$lastSize();
            } else {
                size = GL15.glGetBufferParameteri(GL31.GL_COPY_READ_BUFFER, GL15.GL_BUFFER_SIZE);
            }
            if (size < VS * 4 || size % VS != 0) {
                GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
                continue;
            }
            int dst = GL15.glGenBuffers();
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, dst);
            GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER, (long) size, GL15.GL_STATIC_DRAW);
            GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, 0L, 0L, (long) size);
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
            ids[l] = dst;
            counts[l] = size / VS;
            hashes[l] = tracked ? Capture.layerHash(key, l, size) : 0L;
            if (hashes[l] == 0L) {
                unknown = true;
            }
            total += size;
            any = true;
        }
        if (old != null) {
            ENTRIES.remove(key);
            free(old);
        }
        if (!any) {
            return;
        }
        Entry e = new Entry(key, p);
        System.arraycopy(ids, 0, e.ids, 0, LAYERS);
        System.arraycopy(counts, 0, e.counts, 0, LAYERS);
        System.arraycopy(hashes, 0, e.hash, 0, LAYERS);
        e.stale = unknown;
        e.bytes = total;
        ENTRIES.put(key, e);
        bytes += total;
        captures++;
        if (bytes > BUDGET) {
            evict();
        }
    }

    private static void drop(long key) {
        Entry e = ENTRIES.remove(key);
        if (e != null) {
            free(e);
            drops++;
        }
    }

    private static void free(Entry e) {
        for (int l = 0; l < LAYERS; l++) {
            if (e.ids[l] > 0) {
                GL15.glDeleteBuffers(e.ids[l]);
                e.ids[l] = 0;
            }
        }
        bytes -= e.bytes;
    }

    private static void evict() {
        ArrayList<Entry> all = new ArrayList<Entry>(ENTRIES.values());
        for (Entry e : all) {
            double dx = e.x + 8 - camX;
            double dy = e.y + 8 - camY;
            double dz = e.z + 8 - camZ;
            e.dist2 = dx * dx + dy * dy + dz * dz;
        }
        Collections.sort(all, (a, b) -> Double.compare(b.dist2, a.dist2));
        long target = BUDGET - BUDGET / 10;
        for (Entry e : all) {
            if (bytes <= target) {
                break;
            }
            ENTRIES.remove(e.key);
            free(e);
            evictions++;
        }
    }

    // ================= render =================

    /** Called at the head of RenderGlobal.renderBlockLayer(layer, partialTicks, pass, entity). */
    public static void render(BlockRenderLayer layer, double partialTicks, Entity viewer, ViewFrustum frustum) {
        if (!ENABLED || layer != BlockRenderLayer.SOLID || viewer == null) {
            return;
        }
        try {
            if (!supported()) {
                return;
            }
            Disk.pump(frustum);
            if (ENTRIES.isEmpty()) {
                return;
            }
            renderImpl(partialTicks, viewer, frustum);
        } catch (Throwable t) {
            fail("render", t);
        }
    }

    private static void renderImpl(double pt, Entity e, ViewFrustum frustum) {
        Minecraft mc = Minecraft.func_71410_x();
        if (mc.field_71441_e == null) {
            return;
        }
        camX = e.field_70142_S + (e.field_70165_t - e.field_70142_S) * pt;
        camY = e.field_70137_T + (e.field_70163_u - e.field_70137_T) * pt;
        camZ = e.field_70136_U + (e.field_70161_v - e.field_70136_U) * pt;

        MAT.clear();
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, MAT);
        MAT.get(PROJ);
        MAT.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MAT);
        MAT.get(MV);
        if (Math.abs(PROJ[11] + 1f) > 1e-3f) {
            return;
        }
        double p10 = PROJ[10];
        double p14 = PROJ[14];
        double near = p14 / (p10 - 1.0);
        double far = p14 / (p10 + 1.0);
        if (!(near > 0.0) || !(far > near)) {
            return;
        }
        double newNear = Math.max(near, FAR_NEAR);
        double newFar = Math.max(far, FAR_PLANE);
        PROJ[10] = (float) (-(newFar + newNear) / (newFar - newNear));
        PROJ[14] = (float) (-2.0 * newFar * newNear / (newFar - newNear));
        multiply(PROJ, MV, CLIP);
        extractPlanes(CLIP);

        VISIBLE.clear();
        int skippedVanilla = 0;
        int culled = 0;
        ViewFrustumAccessor vf = frustum == null ? null : (ViewFrustumAccessor) (Object) frustum;
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        long now = System.nanoTime();
        for (Entry en : ENTRIES.values()) {
            float minX = (float) (en.x - camX);
            float minY = (float) (en.y - camY);
            float minZ = (float) (en.z - camZ);
            if (!boxVisible(minX, minY, minZ, minX + 16f, minY + 16f, minZ + 16f)) {
                culled++;
                continue;
            }
            if (vf != null) {
                probe.func_181079_c(en.x, en.y, en.z);
                RenderChunk rc = vf.afterimage$getRenderChunk(probe);
                if (rc != null && rc.func_178568_j().func_177986_g() == en.key
                        && rc.func_178571_g() != CompiledChunk.field_178502_a && chunkLoaded(probe) && settled(en, now)) {
                    skippedVanilla++;
                    continue;
                }
            }
            VISIBLE.add(en);
        }
        lastSkippedVanilla = skippedVanilla;
        lastCulled = culled;
        lastDrawn = VISIBLE.size();
        if (VISIBLE.isEmpty()) {
            return;
        }

        boolean fog = fogEnd <= 0 && GL11.glIsEnabled(GL11.GL_FOG);
        boolean alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        GlStateManager.func_179138_g(OpenGlHelper.field_77476_b);
        boolean lightmap = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        GlStateManager.func_179138_g(OpenGlHelper.field_77478_a);

        GlStateManager.func_179128_n(GL11.GL_PROJECTION);
        GlStateManager.func_179094_E();
        MAT.clear();
        MAT.put(PROJ);
        MAT.flip();
        GL11.glLoadMatrix(MAT);
        GlStateManager.func_179128_n(GL11.GL_MODELVIEW);
        if (fog) {
            GlStateManager.func_179106_n();
        }
        RenderHelper.func_74518_a();
        if (!lightmap) {
            mc.field_71460_t.func_180436_i();
        }

        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        OpenGlHelper.func_77472_b(OpenGlHelper.field_77476_b);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        OpenGlHelper.func_77472_b(OpenGlHelper.field_77478_a);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
        try {
            for (int l = 0; l < LAYERS; l++) {
                if (l == 1) {
                    GlStateManager.func_179141_d();
                }
                for (int i = 0, n = VISIBLE.size(); i < n; i++) {
                    Entry en = VISIBLE.get(i);
                    int id = en.ids[l];
                    if (id <= 0) {
                        continue;
                    }
                    OpenGlHelper.func_176072_g(GL15.GL_ARRAY_BUFFER, id);
                    GL11.glVertexPointer(3, GL11.GL_FLOAT, VS, 0L);
                    GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, VS, 12L);
                    GL11.glTexCoordPointer(2, GL11.GL_FLOAT, VS, 16L);
                    OpenGlHelper.func_77472_b(OpenGlHelper.field_77476_b);
                    GL11.glTexCoordPointer(2, GL11.GL_SHORT, VS, 24L);
                    OpenGlHelper.func_77472_b(OpenGlHelper.field_77478_a);
                    GlStateManager.func_179094_E();
                    GlStateManager.func_179109_b((float) (en.x - camX), (float) (en.y - camY), (float) (en.z - camZ));
                    GlStateManager.func_179109_b(-8.0f, -8.0f, -8.0f);
                    GlStateManager.func_179152_a(1.000001f, 1.000001f, 1.000001f);
                    GlStateManager.func_179109_b(8.0f, 8.0f, 8.0f);
                    GL11.glDrawArrays(GL11.GL_QUADS, 0, en.counts[l]);
                    GlStateManager.func_179121_F();
                }
            }
        } finally {
            OpenGlHelper.func_176072_g(GL15.GL_ARRAY_BUFFER, 0);
            GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
            OpenGlHelper.func_77472_b(OpenGlHelper.field_77476_b);
            GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            OpenGlHelper.func_77472_b(OpenGlHelper.field_77478_a);
            GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
            if (alpha) {
                GlStateManager.func_179141_d();
            } else {
                GlStateManager.func_179118_c();
            }
            if (!lightmap) {
                mc.field_71460_t.func_175072_h();
            }
            if (fog) {
                GlStateManager.func_179127_m();
            }
            GlStateManager.func_179128_n(GL11.GL_PROJECTION);
            GlStateManager.func_179121_F();
            GlStateManager.func_179128_n(GL11.GL_MODELVIEW);
            GlStateManager.func_179086_m(GL11.GL_DEPTH_BUFFER_BIT);
        }
    }

    /** True once vanilla may draw the section alone: same geometry as the copy, or no vanilla change for SETTLE_NANOS. */
    private static boolean settled(Entry e, long now) {
        if (e.handedOver) {
            return true;
        }
        boolean same = true;
        for (int l = 0; l < LAYERS; l++) {
            if (e.hash[l] != e.vanillaHash[l]) {
                same = false;
                break;
            }
        }
        if (same || e.vanillaChanged == 0L || now - e.vanillaChanged > SETTLE_NANOS) {
            e.handedOver = true;
            return true;
        }
        settling++;
        return false;
    }

    /** Column-major 4x4: out = a * b. */
    private static void multiply(float[] a, float[] b, float[] out) {
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                float s = 0f;
                for (int k = 0; k < 4; k++) {
                    s += a[k * 4 + r] * b[c * 4 + k];
                }
                out[c * 4 + r] = s;
            }
        }
    }

    private static void extractPlanes(float[] m) {
        // row r of a column-major matrix: (m[r], m[4+r], m[8+r], m[12+r])
        for (int i = 0; i < 6; i++) {
            int axis = i / 2;
            float sign = (i % 2 == 0) ? 1f : -1f;
            float a = m[3] + sign * m[axis];
            float b = m[7] + sign * m[4 + axis];
            float c = m[11] + sign * m[8 + axis];
            float d = m[15] + sign * m[12 + axis];
            PLANES[i][0] = a;
            PLANES[i][1] = b;
            PLANES[i][2] = c;
            PLANES[i][3] = d;
        }
    }

    private static boolean boxVisible(float x0, float y0, float z0, float x1, float y1, float z1) {
        for (float[] p : PLANES) {
            float px = p[0] >= 0f ? x1 : x0;
            float py = p[1] >= 0f ? y1 : y0;
            float pz = p[2] >= 0f ? z1 : z0;
            if (p[0] * px + p[1] * py + p[2] * pz + p[3] < 0f) {
                return false;
            }
        }
        return true;
    }

    // ================= disk-loaded sections =================

    private static long diskUploads;

    public static long budget() {
        return BUDGET;
    }

    /** Main thread. False when a live copy of that layer exists or vanilla shows the section itself. */
    public static boolean acceptFromDisk(long key, int layer, int x, int y, int z, ViewFrustum frustum) {
        if (!ENABLED || !supported()) {
            return false;
        }
        Entry e = ENTRIES.get(key);
        if (e != null && e.ids[layer] > 0) {
            return false;
        }
        if (frustum != null) {
            BlockPos probe = new BlockPos(x, y, z);
            RenderChunk rc = ((ViewFrustumAccessor) (Object) frustum).afterimage$getRenderChunk(probe);
            if (rc != null && rc.func_178568_j().func_177986_g() == key
                    && rc.func_178571_g() != CompiledChunk.field_178502_a && chunkLoaded(probe)) {
                return false;
            }
        }
        return bytes + data0Size(layer) <= BUDGET;
    }

    private static long data0Size(int layer) {
        return 0L;
    }

    /** Main thread: upload one layer read from disk into a new GPU buffer. */
    public static void uploadLayer(long key, int x, int y, int z, int layer, java.nio.ByteBuffer data) {
        int size = data.remaining();
        if (size < VS * 4 || bytes + size > BUDGET) {
            return;
        }
        int id = GL15.glGenBuffers();
        OpenGlHelper.func_176072_g(GL15.GL_ARRAY_BUFFER, id);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
        OpenGlHelper.func_176072_g(GL15.GL_ARRAY_BUFFER, 0);
        Entry e = ENTRIES.get(key);
        if (e == null) {
            e = new Entry(key, x, y, z);
            ENTRIES.put(key, e);
        }
        e.ids[layer] = id;
        e.counts[layer] = size / VS;
        e.hash[layer] = Disk.diskHash(key, layer);
        if (e.hash[layer] == 0L) {
            e.stale = true;
        }
        e.bytes += size;
        bytes += size;
        diskUploads++;
    }

    // ================= fog =================

    /**
     * EntityViewRenderEvent.RenderFogEvent. Vanilla and OptiFine fire it only for normal linear fog (not liquids, not
     * blindness). While the far zone has content the fog is pushed out, so terrain and far zone fade into the sky together.
     */
    public static void onFog(Object entity, Object state, int mode, float farPlane) {
        int end = fogEnd;
        if (!ENABLED || mode < 0 || end <= farPlane || ENTRIES.isEmpty()) {
            return;
        }
        try {
            if (state instanceof IBlockState && ((IBlockState) state).func_185904_a().func_76224_d()) {
                return;
            }
            if (entity instanceof EntityLivingBase && ((EntityLivingBase) entity).func_70644_a(MobEffects.field_76440_q)) {
                return;
            }
            GlStateManager.func_179102_b(end * 0.6f);
            GlStateManager.func_179153_c((float) end);
        } catch (Throwable t) {
            fail("fog", t);
        }
    }

    // ================= control & stats =================

    public static void clearAll() {
        for (Entry e : ENTRIES.values()) {
            free(e);
        }
        ENTRIES.clear();
        bytes = 0;
    }

    public static String summary() {
        return "far " + (ENABLED ? "ON" : "OFF") + ": sections " + ENTRIES.size() + ", VRAM "
            + String.format("%.1f MB", bytes / 1048576.0) + " of " + (BUDGET >> 20) + " MB, captures " + captures + ", reused " + reused + ", settling frames " + settling + ", fog " + fogEnd
            + ", drops " + drops + ", evictions " + evictions + ", from disk " + diskUploads + " | last frame drawn " + lastDrawn
            + ", vanilla " + lastSkippedVanilla + ", culled " + lastCulled + ", errors " + errors;
    }

    private static void fail(String where, Throwable t) {
        errors++;
        ENABLED = false;
        Capture.logError("far." + where, t);
    }
}
