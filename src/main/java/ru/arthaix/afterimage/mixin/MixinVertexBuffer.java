package ru.arthaix.afterimage.mixin;

import java.nio.ByteBuffer;

import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.client.renderer.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.arthaix.afterimage.Capture;
import ru.arthaix.afterimage.IAfterimageVbo;

/**
 * Observes every upload into a VertexBuffer. Vanilla section uploads and LittleTiles'
 * merged re-uploads (RenderUploader) both end in bufferData, so the bytes seen here
 * are the final section geometry. Read-only: nothing about the upload is changed.
 */
@Mixin(value = VertexBuffer.class, remap = false)
public abstract class MixinVertexBuffer implements IAfterimageVbo {
    @Shadow private int field_177365_a;
    @Shadow private VertexFormat field_177363_b;

    @Unique private RenderChunk afterimage$owner;
    @Unique private int afterimage$layerPlusOne;
    @Unique private int afterimage$lastSize;

    @Inject(method = "<init>(Lnet/minecraft/client/renderer/vertex/VertexFormat;)V", at = @At("RETURN"))
    private void afterimage$created(VertexFormat format, CallbackInfo ci) {
        Capture.onVboCreated();
    }

    @Inject(method = "func_181722_a(Ljava/nio/ByteBuffer;)V", at = @At("HEAD"))
    private void afterimage$upload(ByteBuffer buffer, CallbackInfo ci) {
        Capture.onUpload((VertexBuffer) (Object) this, buffer);
    }

    @Inject(method = "func_177362_c()V", at = @At("HEAD"))
    private void afterimage$delete(CallbackInfo ci) {
        Capture.onDelete((VertexBuffer) (Object) this);
    }

    @Override
    public RenderChunk afterimage$owner() {
        return this.afterimage$owner;
    }

    @Override
    public int afterimage$layer() {
        return this.afterimage$layerPlusOne - 1;
    }

    @Override
    public void afterimage$setOwner(RenderChunk owner, int layer) {
        this.afterimage$owner = owner;
        this.afterimage$layerPlusOne = layer + 1;
    }

    @Override
    public int afterimage$lastSize() {
        return this.afterimage$lastSize;
    }

    @Override
    public void afterimage$setLastSize(int size) {
        this.afterimage$lastSize = size;
    }

    @Override
    public int afterimage$glId() {
        return this.field_177365_a;
    }

    @Override
    public int afterimage$vertexSize() {
        return this.field_177363_b == null ? 0 : this.field_177363_b.func_177338_f();
    }
}
