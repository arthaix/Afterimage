package ru.arthaix.keystone.ltfix.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import ru.arthaix.keystone.ltfix.GeometryPacker;

/**
 * LittleRenderChunk (animated structures and sub-worlds) read a whole VBO back from the GPU in backToRAM to recover tile
 * geometry dropped after upload, as ChunkBlockLayerManager did. The geometry is kept in memory (MixinBufferLink), so
 * there is nothing to recover; uploadBuffer resets its caches right after anyway.
 */
@Mixin(targets = "com.creativemd.littletiles.client.render.entity.LittleRenderChunk", remap = false)
public abstract class MixinLittleRenderChunk {
    @Inject(method = "backToRAM()V", at = @At("HEAD"), cancellable = true)
    private void ltfix$noReadback(CallbackInfo ci) {
        if (GeometryPacker.active()) {
            ci.cancel();
        }
    }
}
