package ru.arthaix.keystone.ltfix.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.creativemd.littletiles.client.render.cache.ChunkBlockLayerCache;

/**
 * ChunkBlockLayerManager.backToRAM read a whole chunk VBO back from the GPU (glGetBufferSubData, a pipeline stall, run on
 * the client thread even when a chunk worker asked for it) to recover the tile geometry dropped after upload. With the
 * geometry kept in memory (MixinBufferLink) there is nothing to recover: the uploaded set is simply forgotten.
 */
@Mixin(targets = "com.creativemd.littletiles.client.render.cache.ChunkBlockLayerManager", remap = false)
public abstract class MixinChunkBlockLayerManager {
    @Shadow
    private ChunkBlockLayerCache uploaded;

    @Inject(method = "backToRAM()V", at = @At("HEAD"), cancellable = true)
    private void ltfix$noReadback(CallbackInfo ci) {
        synchronized (this) {
            this.uploaded = null;
        }
        ci.cancel();
    }
}
