package ru.arthaix.afterimage.mixin;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.arthaix.afterimage.sync.ChangeTracker;

/**
 * Server change tracking: World.notifyBlockUpdate, the call that makes clients re-render a block. Besides block changes
 * it is how tile entities (LittleTiles, Chisels & Bits and others) announce that their own rendering changed.
 */
@Mixin(value = World.class, remap = false)
public abstract class MixinWorldChanges {
    @Inject(method = "func_184138_a(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/block/state/IBlockState;I)V",
            at = @At("HEAD"))
    private void afterimage$notify(BlockPos pos, IBlockState oldState, IBlockState newState, int flags, CallbackInfo ci) {
        ChangeTracker.onChange((World) (Object) this, pos.func_177958_n() >> 4, pos.func_177952_p() >> 4);
    }
}
