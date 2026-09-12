package ru.arthaix.keystone.teunloadbatch.mixin;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.arthaix.keystone.teunloadbatch.DeferredRemovalList;
import ru.arthaix.keystone.teunloadbatch.EditStats;

/**
 * World.tickableTileEntities becomes a DeferredRemovalList, and World.removeTileEntity records its removal from that
 * list instead of scanning it. The call is found as the first List.remove after the tickableTileEntities field read, so
 * it matches both vanilla's and Forge's layout of the method.
 */
@Mixin(value = World.class, remap = false)
public abstract class MixinWorldTickableRemoval {
    /** tickableTileEntities */
    @Shadow @Final @Mutable public List<TileEntity> field_175730_i;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void teunloadbatch$deferTickableRemoval(CallbackInfo ci) {
        List<TileEntity> list = this.field_175730_i;
        if (EditStats.DEFER_TICKABLE && list != null && list.getClass() == ArrayList.class) {
            this.field_175730_i = new DeferredRemovalList<TileEntity>(list);
        }
    }

    // opcode 180 = GETFIELD
    @Redirect(method = "func_175713_t(Lnet/minecraft/util/math/BlockPos;)V",
              slice = @Slice(from = @At(value = "FIELD", target = "Lnet/minecraft/world/World;field_175730_i:Ljava/util/List;", opcode = 180)),
              at = @At(value = "INVOKE", target = "Ljava/util/List;remove(Ljava/lang/Object;)Z", ordinal = 0))
    private boolean teunloadbatch$removeTickableLater(List<TileEntity> list, Object te) {
        if (list instanceof DeferredRemovalList) {
            ((DeferredRemovalList<?>) list).removeLater(te);
            return true;
        }
        return list.remove(te);
    }
}
