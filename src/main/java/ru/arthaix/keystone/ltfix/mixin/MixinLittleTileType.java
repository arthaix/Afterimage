package ru.arthaix.keystone.ltfix.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.creativemd.littletiles.common.tile.LittleTile;

import ru.arthaix.keystone.ltfix.TileConstructors;

/**
 * LittleTileType.createTile called Class.getConstructor (a security check plus a copy of the constructor array) for every
 * tile it created, and a dense chunk creates hundreds of thousands of tiles while it loads, on both sides.
 */
@Mixin(targets = "com.creativemd.littletiles.common.tile.registry.LittleTileType", remap = false)
public abstract class MixinLittleTileType {
    @Shadow
    @Final
    public String id;

    @Shadow
    @Final
    public Class<? extends LittleTile> clazz;

    @Inject(method = "createTile", at = @At("HEAD"), cancellable = true)
    private void ltfix$cachedConstructor(CallbackInfoReturnable<LittleTile> cir) {
        cir.setReturnValue((LittleTile) TileConstructors.create(this.clazz, this.id));
    }
}
