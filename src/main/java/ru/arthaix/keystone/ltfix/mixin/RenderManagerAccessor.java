package ru.arthaix.keystone.ltfix.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Render state of a LittleTiles tile entity, for the no-geometry metric. */
@Mixin(targets = "com.creativemd.littletiles.client.render.world.TileEntityRenderManager", remap = false)
public interface RenderManagerAccessor {
    @Accessor(value = "finishedIndex", remap = false)
    int ltfix$finishedIndex();
}
