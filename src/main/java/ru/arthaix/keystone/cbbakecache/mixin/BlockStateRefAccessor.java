package ru.arthaix.keystone.cbbakecache.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the package-private stateID of C&B's BlockStateRef (full-block neighbours). */
@Mixin(targets = "mod.chiselsandbits.render.chiseledblock.BlockStateRef", remap = false)
public interface BlockStateRefAccessor {
    @Accessor(value = "stateID", remap = false)
    int cbbakecache$getStateID();
}
