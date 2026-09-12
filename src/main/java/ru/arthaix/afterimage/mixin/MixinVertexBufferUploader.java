package ru.arthaix.afterimage.mixin;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.VertexBufferUploader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.arthaix.afterimage.Capture;

/** Tells the upload hook which BufferBuilder the bytes come from, so it can use the chunk worker's fingerprint. */
@Mixin(value = VertexBufferUploader.class, remap = false)
public abstract class MixinVertexBufferUploader {
    @Inject(method = "func_181679_a(Lnet/minecraft/client/renderer/BufferBuilder;)V", at = @At("HEAD"))
    private void afterimage$uploading(BufferBuilder buffer, CallbackInfo ci) {
        Capture.uploading(buffer);
    }

    @Inject(method = "func_181679_a(Lnet/minecraft/client/renderer/BufferBuilder;)V", at = @At("RETURN"))
    private void afterimage$uploaded(BufferBuilder buffer, CallbackInfo ci) {
        Capture.uploading(null);
    }
}
