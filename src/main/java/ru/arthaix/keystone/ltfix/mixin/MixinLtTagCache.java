package ru.arthaix.keystone.ltfix.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.creativemd.littletiles.common.tile.parent.TileList;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import ru.arthaix.keystone.teunloadbatch.TagCache;
import ru.arthaix.keystone.teunloadbatch.TagCacheVeto;

/**
 * LittleTiles side of the server's chunk-packet tag cache (teunloadbatch TagCache): every way LittleTiles changes a
 * tile entity's tiles voids the cached bytes, and tile entities with structures (doors, signals, animations keep state
 * that changes without those calls) or ticking tiles are never cached.
 */
@Mixin(targets = "com.creativemd.littletiles.common.tileentity.TileEntityLittleTiles", remap = false)
public abstract class MixinLtTagCache implements TagCacheVeto {
    @Shadow
    protected TileList tiles;

    @Override
    public boolean teunloadbatch$vetoTagCache() {
        TileList t = this.tiles;
        return t == null || this instanceof ITickable || t.countStructures() > 0;
    }

    @Inject(method = "updateTiles(Z)V", at = @At("HEAD"), require = 0)
    private void ltfix$tilesUpdating(CallbackInfo ci) {
        TagCache.bump((TileEntity) (Object) this);
    }

    @Inject(method = "updateTilesSecretly(Ljava/util/function/Consumer;)V", at = @At("RETURN"), require = 0)
    private void ltfix$tilesChangedSecretly(CallbackInfo ci) {
        TagCache.bump((TileEntity) (Object) this);
    }

    @Inject(method = "customTilesUpdate()V", at = @At("HEAD"), require = 0)
    private void ltfix$customUpdate(CallbackInfo ci) {
        TagCache.bump((TileEntity) (Object) this);
    }
}
