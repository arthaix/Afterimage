package ru.arthaix.keystone.teunloadbatch.mixin;

import java.util.List;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.arthaix.keystone.teunloadbatch.IndexedTileEntityList;

/**
 * Replaces World.loadedTileEntityList with an indexed ArrayList right after the world is constructed.
 *
 * With millions of Chisels & Bits / LittleTiles tile entities loaded, every vanilla remove(Object), removeAll and
 * contains on that list was a scan over millions of references: one per replaced block (World.removeTileEntity),
 * one per tile entity added while ticking, and one removeAll per tick while chunks unload. The indexed list makes all
 * of them constant time per element, so nothing has to be deferred or flushed.
 */
@Mixin(value = World.class, remap = false)
public abstract class MixinWorld {
    /** loadedTileEntityList */
    @Shadow @Final @Mutable public List<TileEntity> field_147482_g;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void teunloadbatch$indexLoadedTileEntities(CallbackInfo ci) {
        if (!(this.field_147482_g instanceof IndexedTileEntityList)) {
            this.field_147482_g = new IndexedTileEntityList(this.field_147482_g);
        }
    }
}
