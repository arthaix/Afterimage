package ru.arthaix.afterimage.mixin;

import net.minecraft.client.renderer.ChunkRenderContainer;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.BlockRenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.arthaix.afterimage.Far;
import ru.arthaix.afterimage.IAfterimageRenderChunk;

/**
 * ChunkRenderContainer.addRenderChunk: vanilla (and OptiFine's VboRenderList, which does not override it) collects the
 * sections to draw per layer here. A section that the far zone marked this frame as "vanilla still assembling" is left
 * out, so its complete copy is seen instead of a half-built one.
 */
@Mixin(value = ChunkRenderContainer.class, remap = false)
public abstract class MixinChunkRenderContainer {
    @Inject(method = "func_178002_a(Lnet/minecraft/client/renderer/chunk/RenderChunk;Lnet/minecraft/util/BlockRenderLayer;)V",
            at = @At("HEAD"), cancellable = true)
    private void afterimage$hideAssembling(RenderChunk renderChunk, BlockRenderLayer layer, CallbackInfo ci) {
        if (((IAfterimageRenderChunk) (Object) renderChunk).afterimage$hideFrame() == Far.frame()) {
            ci.cancel();
        }
    }
}
