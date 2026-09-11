package ru.arthaix.afterimage.mixin;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.arthaix.afterimage.sync.ChangeTracker;

/** Server change tracking: block state changes, and the chunk load window in which notifications are not changes. */
@Mixin(value = Chunk.class, remap = false)
public abstract class MixinChunkChanges {
    @Inject(method = "func_177436_a(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;)Lnet/minecraft/block/state/IBlockState;",
            at = @At("RETURN"))
    private void afterimage$blockChanged(BlockPos pos, IBlockState state, CallbackInfoReturnable<IBlockState> cir) {
        if (cir.getReturnValue() != null) {
            Chunk c = (Chunk) (Object) this;
            ChangeTracker.onChange(c.func_177412_p(), c.field_76635_g, c.field_76647_h);
        }
    }

    @Inject(method = "func_76631_c()V", at = @At("HEAD"))
    private void afterimage$loadStart(CallbackInfo ci) {
        ChangeTracker.loadStart(((Chunk) (Object) this).func_177412_p());
    }

    @Inject(method = "func_76631_c()V", at = @At("RETURN"))
    private void afterimage$loadEnd(CallbackInfo ci) {
        Chunk c = (Chunk) (Object) this;
        ChangeTracker.loadEnd(c.func_177412_p(), c.field_76635_g, c.field_76647_h);
    }
}
