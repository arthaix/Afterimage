package ru.arthaix.keystone.teunloadbatch.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.chunk.Chunk;

/**
 * Chunk.getEntitiesWithinAABBForEntity / getEntitiesOfTypeWithinAABB call getEntityBoundingBox().intersects(...) on every
 * entity (and entity part) in range. A modded entity that is not set up yet can answer null, and the NullPointerException
 * in the ticking player kicked the player with "Internal server error" (2026-09-12 13:23, flying over the city). Such an
 * entity now counts as intersecting nothing, the same as vanilla does for a null collision box.
 */
@Mixin(value = Chunk.class, remap = false)
public abstract class MixinChunkEntityBoxes {
    @Unique
    private static final AxisAlignedBB teunloadbatch$NOWHERE = new AxisAlignedBB(3.0E7D, -1.0E4D, 3.0E7D, 3.0E7D, -1.0E4D, 3.0E7D);

    @Redirect(method = { "func_177414_a", "func_177430_a" },
              at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;func_174813_aQ()Lnet/minecraft/util/math/AxisAlignedBB;"),
              require = 0)
    private AxisAlignedBB teunloadbatch$boxOrNowhere(Entity entity) {
        AxisAlignedBB box = entity == null ? null : entity.func_174813_aQ();
        return box != null ? box : teunloadbatch$NOWHERE;
    }
}
