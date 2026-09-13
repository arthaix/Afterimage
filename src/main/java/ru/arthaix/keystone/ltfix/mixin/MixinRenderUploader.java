package ru.arthaix.keystone.ltfix.mixin;

import java.nio.ByteBuffer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import ru.arthaix.keystone.ltfix.DirectScratch;

/** See DirectScratch: the per-upload direct buffers of LittleTiles' RenderUploader are reused. */
@Mixin(targets = "com.creativemd.littletiles.client.render.world.RenderUploader", remap = false)
public abstract class MixinRenderUploader {
    @Redirect(method = "uploadRenderData", at = @At(value = "INVOKE", target = "Ljava/nio/ByteBuffer;allocateDirect(I)Ljava/nio/ByteBuffer;"))
    private static ByteBuffer ltfix$uploadScratch(int size) {
        return DirectScratch.forUpload(size);
    }

    @Redirect(method = "glMapBufferRange", at = @At(value = "INVOKE", target = "Ljava/nio/ByteBuffer;allocateDirect(I)Ljava/nio/ByteBuffer;"))
    private static ByteBuffer ltfix$readbackScratch(int size) {
        return DirectScratch.forReadback(size);
    }
}
