package ru.arthaix.afterimage.mixin;

import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.arthaix.afterimage.Capture;
import ru.arthaix.afterimage.Disk;
import ru.arthaix.afterimage.Far;
import ru.arthaix.afterimage.IAfterimageRenderChunk;

/**
 * - getVertexBufferByLayer: every upload path (vanilla, LittleTiles) fetches the buffer through it first,
 *   so the buffer learns which section and layer it belongs to.
 * - setNeedsUpdate / setPosition: dirty generation counter for the phase 0 verifier.
 * - setPosition / deleteGlResources / setCompiledChunk: phase 1 far-zone capture points, all at HEAD so the
 *   section's buffers still hold the geometry that is about to be lost.
 */
@Mixin(value = RenderChunk.class, remap = false)
public abstract class MixinRenderChunk implements IAfterimageRenderChunk {
    @Unique private int afterimage$dirtyGen;
    @Unique private int afterimage$hideFrame = -1;

    @Inject(method = "func_178565_b(I)Lnet/minecraft/client/renderer/vertex/VertexBuffer;", at = @At("RETURN"))
    private void afterimage$bindBuffer(int layer, CallbackInfoReturnable<VertexBuffer> cir) {
        Capture.bindOwner((RenderChunk) (Object) this, layer, cir.getReturnValue());
    }

    @Inject(method = "func_178575_a(Z)V", at = @At("HEAD"))
    private void afterimage$markDirty(boolean immediate, CallbackInfo ci) {
        if (!Capture.selfMark) {
            this.afterimage$dirtyGen++;
        }
    }

    @Inject(method = "func_189562_a(III)V", at = @At("HEAD"))
    private void afterimage$moved(int x, int y, int z, CallbackInfo ci) {
        Far.onLeave((RenderChunk) (Object) this);
        Disk.dropHeld((RenderChunk) (Object) this);
        this.afterimage$dirtyGen++;
    }

    @Inject(method = "func_178566_a()V", at = @At("HEAD"))
    private void afterimage$deleting(CallbackInfo ci) {
        Far.onLeave((RenderChunk) (Object) this);
        Disk.dropHeld((RenderChunk) (Object) this);
    }

    @Inject(method = "func_178580_a(Lnet/minecraft/client/renderer/chunk/CompiledChunk;)V", at = @At("HEAD"))
    private void afterimage$compiled(CompiledChunk next, CallbackInfo ci) {
        Disk.onCompiled((RenderChunk) (Object) this, next);
        Far.onCompiledReplace((RenderChunk) (Object) this, next);
    }

    @Override
    public int afterimage$dirtyGen() {
        return this.afterimage$dirtyGen;
    }

    @Override
    public int afterimage$hideFrame() {
        return this.afterimage$hideFrame;
    }

    @Override
    public void afterimage$setHideFrame(int frame) {
        this.afterimage$hideFrame = frame;
    }
}
