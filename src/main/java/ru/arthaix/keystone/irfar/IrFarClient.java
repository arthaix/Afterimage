package ru.arthaix.keystone.irfar;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.MinecraftForgeClient;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Client half of IrFar (see there). Client thread only. */
public final class IrFarClient {
    private static final int MAX_KEPT = Integer.getInteger("irfar.maxKeptTiles", 50000);
    /** Forge's TileEntity.getRenderBoundingBox() and shouldRenderInPass(int), absent from the vanilla compile classpath */
    private static final MethodHandle RENDER_BOX = forgeTileMethod("getRenderBoundingBox", MethodType.methodType(AxisAlignedBB.class));
    private static final MethodHandle IN_PASS = forgeTileMethod("shouldRenderInPass", MethodType.methodType(boolean.class, int.class));

    /** UMC entities drawn this frame by any pass */
    private static final Set<Entity> DRAWN = Collections.newSetFromMap(new IdentityHashMap<Entity, Boolean>());
    /** chunk -> UMC tile entities with a model, kept after the client unloaded the chunk */
    private static final Map<Long, List<TileEntity>> KEPT = new HashMap<Long, List<TileEntity>>();
    private static final List<TileEntity> VISIBLE = new ArrayList<TileEntity>();
    private static World keptWorld;
    private static int keptCount;
    private static boolean renderFailed;

    private static boolean shadowResolved;
    private static Field shadowPass;
    private static boolean umcResolved;
    private static Method instance;
    private static Map<?, ?> blockRenderers;

    public static int rangeBlocks() {
        return IrFar.rangeBlocks(Minecraft.func_71410_x().field_71474_y.field_151451_c);
    }

    /** From RenderManager.renderEntityStatic. The shadow pass of shader packs does not count. */
    public static void markDrawn(Entity entity) {
        if (!shadowPass()) {
            DRAWN.add(entity);
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            DRAWN.clear();
        }
    }

    /** Entities outside the chunks the client has loaded are not updated by vanilla: trains would stop in place. */
    @SubscribeEvent
    public void onCanUpdate(EntityEvent.CanUpdate event) {
        Entity e = event.getEntity();
        if (e.field_70170_p != null && e.field_70170_p.field_72995_K && IrFar.isFarEntity(e)) {
            event.setCanUpdate(true);
        }
    }

    /**
     * Replaces UniversalModCore's large-entity pass (opaque pass of the tile entity renderers, after vanilla drew the
     * entities of the visible render sections): every UMC entity in range and in view that nothing drew this frame.
     */
    public static void drawEntities(float partialTicks) {
        Minecraft mc = Minecraft.func_71410_x();
        World world = mc.field_71441_e;
        Entity view = mc.func_175606_aa();
        if (world == null || view == null) {
            return;
        }
        double cx = view.field_70142_S + (view.field_70165_t - view.field_70142_S) * partialTicks;
        double cy = view.field_70137_T + (view.field_70163_u - view.field_70137_T) * partialTicks;
        double cz = view.field_70136_U + (view.field_70161_v - view.field_70136_U) * partialTicks;
        Frustum camera = new Frustum();
        camera.func_78547_a(cx, cy, cz);
        double r = rangeBlocks();
        double r2 = r * r;
        RenderManager renderManager = mc.func_175598_ae();
        List<Entity> entities = world.field_72996_f;
        for (int i = 0; i < entities.size(); i++) {
            Entity e = entities.get(i);
            if (!IrFar.isModdedEntity(e) || DRAWN.contains(e)) {
                continue;
            }
            if (e.func_70092_e(cx, cy, cz) > r2 || !camera.func_78546_a(e.func_184177_bl())) {
                continue;
            }
            renderManager.func_188388_a(e, partialTicks, true);
        }
    }

    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        World world = event.getWorld();
        if (!IrFar.ENABLED || world == null || !world.field_72995_K) {
            return;
        }
        Entity view = Minecraft.func_71410_x().func_175606_aa();
        if (view == null) {
            return;
        }
        if (world != keptWorld) {
            clearKept(world);
        }
        Chunk chunk = event.getChunk();
        double r = rangeBlocks() + 32.0;
        List<TileEntity> keep = null;
        for (TileEntity te : chunk.func_177434_r().values()) {
            if (!IrFar.isFarTile(te) || dist2(view, te.func_174877_v()) > r * r || !hasModel(te)) {
                continue;
            }
            if (keep == null) {
                keep = new ArrayList<TileEntity>();
            }
            keep.add(te);
        }
        if (keep != null && keptCount + keep.size() <= MAX_KEPT) {
            List<TileEntity> old = KEPT.put(ChunkPos.func_77272_a(chunk.field_76635_g, chunk.field_76647_h), keep);
            keptCount += keep.size() - (old == null ? 0 : old.size());
        }
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        World world = event.getWorld();
        if (world != null && world == keptWorld && !KEPT.isEmpty()) {
            Chunk chunk = event.getChunk();
            List<TileEntity> old = KEPT.remove(ChunkPos.func_77272_a(chunk.field_76635_g, chunk.field_76647_h));
            if (old != null) {
                keptCount -= old.size();
            }
        }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld() == keptWorld) {
            clearKept(null);
        }
    }

    /** Kept tile entities: the tile entity renderers in the opaque and the translucent pass. */
    @SubscribeEvent
    public void onRenderLast(RenderWorldLastEvent event) {
        if (KEPT.isEmpty() || renderFailed) {
            return;
        }
        Minecraft mc = Minecraft.func_71410_x();
        World world = mc.field_71441_e;
        Entity view = mc.func_175606_aa();
        if (world != keptWorld) {
            clearKept(world);
            return;
        }
        if (view == null) {
            return;
        }
        float pt = event.getPartialTicks();
        double cx = view.field_70142_S + (view.field_70165_t - view.field_70142_S) * pt;
        double cy = view.field_70137_T + (view.field_70163_u - view.field_70137_T) * pt;
        double cz = view.field_70136_U + (view.field_70161_v - view.field_70136_U) * pt;
        Frustum camera = new Frustum();
        camera.func_78547_a(cx, cy, cz);
        double r = rangeBlocks();
        double r2 = r * r;
        double drop = (r * 1.5 + 32.0) * (r * 1.5 + 32.0);
        IChunkProvider chunks = world.func_72863_F();
        for (Iterator<List<TileEntity>> it = KEPT.values().iterator(); it.hasNext();) {
            List<TileEntity> list = it.next();
            BlockPos first = list.get(0).func_174877_v();
            if (chunks.func_186026_b(first.func_177958_n() >> 4, first.func_177952_p() >> 4) != null || dist2(cx, cz, first) > drop) {
                // loaded again (its own tile entities draw) or far out of range
                it.remove();
                keptCount -= list.size();
                continue;
            }
            for (TileEntity te : list) {
                BlockPos p = te.func_174877_v();
                if (dist2(cx, cz, p) <= r2 && camera.func_78546_a(renderBox(te))) {
                    VISIBLE.add(te);
                }
            }
        }
        if (VISIBLE.isEmpty()) {
            return;
        }
        TileEntityRendererDispatcher dispatcher = TileEntityRendererDispatcher.field_147556_a;
        int pass = MinecraftForgeClient.getRenderPass();
        mc.field_71460_t.func_180436_i();
        RenderHelper.func_74519_b();
        GlStateManager.func_179131_c(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.func_179126_j();
        GlStateManager.func_179132_a(true);
        try {
            ForgeHooksClient.setRenderPass(0);
            for (TileEntity te : VISIBLE) {
                if (inPass(te, 0)) {
                    dispatcher.func_180546_a(te, pt, -1);
                }
            }
            GlStateManager.func_179147_l();
            GlStateManager.func_179120_a(770, 771, 1, 0);
            ForgeHooksClient.setRenderPass(1);
            for (TileEntity te : VISIBLE) {
                if (inPass(te, 1)) {
                    dispatcher.func_180546_a(te, pt, -1);
                }
            }
        } catch (Throwable t) {
            renderFailed = true;
            System.out.println("[irfar] drawing kept tile entities failed, stopped for this session: " + t);
            t.printStackTrace();
        } finally {
            GlStateManager.func_179084_k();
            ForgeHooksClient.setRenderPass(pass);
            RenderHelper.func_74518_a();
            mc.field_71460_t.func_175072_h();
            VISIBLE.clear();
        }
    }

    private static MethodHandle forgeTileMethod(String name, MethodType type) {
        try {
            return MethodHandles.publicLookup().findVirtual(TileEntity.class, name, type);
        } catch (Throwable t) {
            return null;
        }
    }

    private static AxisAlignedBB renderBox(TileEntity te) {
        try {
            return (AxisAlignedBB) RENDER_BOX.invokeExact(te);
        } catch (Throwable t) {
            BlockPos p = te.func_174877_v();
            return new AxisAlignedBB(p.func_177958_n() - 32, p.func_177956_o() - 32, p.func_177952_p() - 32, p.func_177958_n() + 33, p.func_177956_o() + 33, p.func_177952_p() + 33);
        }
    }

    private static boolean inPass(TileEntity te, int pass) {
        try {
            return (boolean) IN_PASS.invokeExact(te, pass);
        } catch (Throwable t) {
            return pass == 0;
        }
    }

    public static int keptTiles() {
        return keptCount;
    }

    private static void clearKept(World world) {
        KEPT.clear();
        keptCount = 0;
        keptWorld = world;
    }

    private static double dist2(Entity view, BlockPos p) {
        return dist2(view.field_70165_t, view.field_70161_v, p);
    }

    private static double dist2(double x, double z, BlockPos p) {
        double dx = p.func_177958_n() + 0.5 - x;
        double dz = p.func_177952_p() + 0.5 - z;
        return dx * dx + dz * dz;
    }

    /** UMC draws a tile entity only when a model is registered for its block entity class (rail gags have none). */
    private static boolean hasModel(TileEntity te) {
        if (!umcResolved) {
            umcResolved = true;
            try {
                instance = te.getClass().getMethod("instance");
                Field f = Class.forName("cam72cam.mod.render.BlockRender").getDeclaredField("renderers");
                f.setAccessible(true);
                blockRenderers = (Map<?, ?>) f.get(null);
            } catch (Throwable t) {
                System.out.println("[irfar] UniversalModCore block renderers not found, far tile entities off: " + t);
            }
        }
        if (instance == null || blockRenderers == null) {
            return false;
        }
        try {
            Object blockEntity = instance.invoke(te);
            return blockEntity != null && blockRenderers.containsKey(blockEntity.getClass());
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean shadowPass() {
        if (!shadowResolved) {
            shadowResolved = true;
            try {
                Field f = Class.forName("net.optifine.shaders.Shaders").getDeclaredField("isShadowPass");
                f.setAccessible(true);
                shadowPass = f;
            } catch (Throwable t) {
                shadowPass = null;
            }
        }
        try {
            return shadowPass != null && shadowPass.getBoolean(null);
        } catch (Throwable t) {
            return false;
        }
    }
}
