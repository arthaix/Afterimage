package ru.arthaix.keystone.umctickfix.mixin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.tileentity.TileEntity;

/**
 * UMC's BlockRender puts its tile entities on RenderGlobal's global render list from a client tick handler (the lambda
 * subscribed to ClientEvents.TICK in its static initializer). Every tick it copied the whole loaded tile entity list,
 * filtered it with a stream, and called RenderGlobal.updateTileEntities(previous, current) with two ArrayLists; the
 * HashSet.removeAll inside compares each global entry against the previous list, which is quadratic. 1.0.0 only
 * throttled that to once a second.
 *
 * 1.1.1 keeps the UMC tile entities in an indexed list and does bounded work per tick: the next -Dumctickfix.slice
 * (20,000) entries of the world's tile entity list are checked for new ones, and a rotating share of the tracked ones
 * (at least 1,000, all of them within about a second) is re-checked for leaving the world or changing whether they
 * render. RenderGlobal only receives what changed, removals as a set. That needs an O(1) contains on the world list
 * (teunloadbatch's indexed list); on a plain list the 1.0.0 throttle applies.
 */
@Mixin(targets = "cam72cam.mod.render.BlockRender", remap = false)
public class MixinBlockRender {
    @Unique
    private static final int umctickfix$SLICE = Math.max(1000, Integer.getInteger("umctickfix.slice", 20_000));
    @Unique
    private static final long umctickfix$INTERVAL_NANOS = 1_000_000_000L;
    @Unique
    private static final List<TileEntity> umctickfix$tracked = new ArrayList<TileEntity>();
    @Unique
    private static final IdentityHashMap<TileEntity, Integer> umctickfix$slot = new IdentityHashMap<TileEntity, Integer>();
    @Unique
    private static final Set<TileEntity> umctickfix$shown = Collections.newSetFromMap(new IdentityHashMap<TileEntity, Boolean>());
    @Unique
    private static WorldClient umctickfix$world;
    @Unique
    private static int umctickfix$listCursor;
    @Unique
    private static int umctickfix$trackedCursor;
    @Unique
    private static long umctickfix$lastScanNanos;

    @Inject(method = "lambda$static$2()V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void umctickfix$trackIncrementally(CallbackInfo ci) {
        Minecraft mc = Minecraft.func_71410_x();
        WorldClient w = mc.field_71441_e;
        if (w == null) {
            return;
        }
        List<TileEntity> all = w.field_147482_g;
        if (!all.getClass().getName().endsWith(".IndexedTileEntityList")) {
            // plain list: contains() is a linear scan, so keep UMC's own pass, at most once a second
            long now = System.nanoTime();
            if (now - umctickfix$lastScanNanos < umctickfix$INTERVAL_NANOS) {
                ci.cancel();
            } else {
                umctickfix$lastScanNanos = now;
            }
            return;
        }
        ci.cancel();
        if (w != umctickfix$world) {
            umctickfix$tracked.clear();
            umctickfix$slot.clear();
            umctickfix$shown.clear();
            umctickfix$listCursor = 0;
            umctickfix$trackedCursor = 0;
            umctickfix$world = w;
        }
        Set<TileEntity> toRemove = null;
        List<TileEntity> toAdd = null;

        // new UMC tile entities from the next slice of the world list
        int n = all.size();
        for (int k = 0, limit = Math.min(umctickfix$SLICE, n); k < limit; k++) {
            if (umctickfix$listCursor >= n) {
                umctickfix$listCursor = 0;
            }
            TileEntity te = all.get(umctickfix$listCursor++);
            if (te instanceof cam72cam.mod.block.tile.TileEntity && !umctickfix$slot.containsKey(te)) {
                umctickfix$slot.put(te, umctickfix$tracked.size());
                umctickfix$tracked.add(te);
                if (umctickfix$renders(te)) {
                    umctickfix$shown.add(te);
                    if (toAdd == null) {
                        toAdd = new ArrayList<TileEntity>();
                    }
                    toAdd.add(te);
                }
            }
        }

        // a rotating share of the tracked ones: gone from the world, or render state changed
        int size = umctickfix$tracked.size();
        for (int k = 0, limit = Math.min(size, Math.max(1000, size / 20)); k < limit && !umctickfix$tracked.isEmpty(); k++) {
            if (umctickfix$trackedCursor >= umctickfix$tracked.size()) {
                umctickfix$trackedCursor = 0;
            }
            int i = umctickfix$trackedCursor;
            TileEntity te = umctickfix$tracked.get(i);
            boolean gone = te.func_145837_r() || te.func_145831_w() != w || !all.contains(te);
            boolean renders = !gone && umctickfix$renders(te);
            boolean shown = umctickfix$shown.contains(te);
            if (shown && !renders) {
                umctickfix$shown.remove(te);
                if (toRemove == null) {
                    toRemove = Collections.newSetFromMap(new IdentityHashMap<TileEntity, Boolean>());
                }
                toRemove.add(te);
            } else if (!shown && renders) {
                umctickfix$shown.add(te);
                if (toAdd == null) {
                    toAdd = new ArrayList<TileEntity>();
                }
                toAdd.add(te);
            }
            if (gone) {
                // swap-remove; the moved element is looked at on this position next
                int last = umctickfix$tracked.size() - 1;
                TileEntity moved = umctickfix$tracked.remove(last);
                umctickfix$slot.remove(te);
                if (i != last) {
                    umctickfix$tracked.set(i, moved);
                    umctickfix$slot.put(moved, i);
                }
            } else {
                umctickfix$trackedCursor++;
            }
        }

        if (toRemove != null || toAdd != null) {
            mc.field_71438_f.func_181023_a(toRemove == null ? Collections.<TileEntity>emptySet() : toRemove,
                toAdd == null ? Collections.<TileEntity>emptyList() : toAdd);
        }
    }

    @Unique
    private static boolean umctickfix$renders(TileEntity te) {
        return ((cam72cam.mod.block.tile.TileEntity) te).isLoaded() && te.func_145833_n() > 0.0D;
    }
}
