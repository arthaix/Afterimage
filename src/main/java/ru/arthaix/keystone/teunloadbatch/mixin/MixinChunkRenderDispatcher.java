package ru.arthaix.keystone.teunloadbatch.mixin;

import java.util.List;
import java.util.concurrent.BlockingQueue;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.renderer.RegionRenderCacheBuilder;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import ru.arthaix.keystone.teunloadbatch.RenderBuilders;

/** Bounds the native memory of the chunk render builder pool (see RenderBuilders). */
@Mixin(value = ChunkRenderDispatcher.class, remap = false)
public abstract class MixinChunkRenderDispatcher {
    @Shadow
    @Final
    @Mutable
    private int field_188249_c;

    @Shadow
    @Final
    private BlockingQueue<RegionRenderCacheBuilder> field_178520_e;

    @Shadow
    @Final
    private List<Thread> field_188250_d;

    /** countRenderBuilders = clamp(workers * 10, 1, memory limit) */
    @ModifyConstant(method = "<init>()V", constant = @Constant(intValue = 10), require = 0)
    private int teunloadbatch$buildersPerWorker(int vanilla) {
        return RenderBuilders.PER_WORKER;
    }

    /**
     * Whatever computed the count, keep buildersPerWorker per worker thread (at least 8): the surplus builders are taken
     * out of the pool before any worker runs, and countRenderBuilders is lowered so stopWorkerThreads drains the right number.
     */
    @Inject(method = "<init>()V", at = @At("RETURN"), require = 0)
    private void teunloadbatch$trimPool(CallbackInfo ci) {
        int target = Math.max(8, Math.max(1, this.field_188250_d.size()) * RenderBuilders.PER_WORKER);
        int before = this.field_188249_c;
        int removed = 0;
        while (this.field_188249_c > target && this.field_178520_e.poll() != null) {
            this.field_188249_c--;
            removed++;
        }
        RenderBuilders.logPool(before, this.field_188249_c, this.field_188250_d.size(), removed);
    }

    @Inject(method = "func_178512_a", at = @At("HEAD"), require = 0)
    private void teunloadbatch$builderReturned(RegionRenderCacheBuilder builder, CallbackInfo ci) {
        RenderBuilders.onReturn(builder);
    }

    @Inject(method = "func_178515_c", at = @At("RETURN"), cancellable = true, require = 0)
    private void teunloadbatch$builderTaken(CallbackInfoReturnable<RegionRenderCacheBuilder> cir) {
        RegionRenderCacheBuilder builder = cir.getReturnValue();
        RegionRenderCacheBuilder use = RenderBuilders.onTake(builder);
        if (use != builder) {
            cir.setReturnValue(use);
        }
    }
}
