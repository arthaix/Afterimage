package ru.arthaix.keystone.ltfix.mixin;

import java.nio.ByteBuffer;
import java.util.zip.Deflater;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import ru.arthaix.keystone.ltfix.GeometryPacker;
import ru.arthaix.keystone.ltfix.PackableLink;

/**
 * 1. BufferLink.uploaded dropped the tile entity's copy of its geometry once the chunk VBO was uploaded; LittleTiles then
 *    read it back from the VBO before the next rebuild. Any VBO that was deleted, recreated or refilled in between gave the
 *    tiles nothing (they vanished) or another chunk's bytes (stripes and artifacts). The copy is now kept in memory.
 * 2. A kept copy nobody reads is packed by GeometryPacker. Every read of the geometry goes through byteBuffer(), which
 *    holds this link's monitor and unpacks first, so no reader sees a link while it is being packed.
 */
@Mixin(targets = "com.creativemd.littletiles.client.render.cache.BufferLink", remap = false)
public abstract class MixinBufferLink implements PackableLink {
    @Shadow
    private ByteBuffer byteBuffer;

    @Shadow
    private boolean uploaded;

    @Shadow
    @Final
    public int length;

    @Unique
    private byte[] ltfix$packed;

    @Unique
    private volatile long ltfix$touched;

    @Inject(method = "<init>(Ljava/nio/ByteBuffer;II)V", at = @At("RETURN"))
    private void ltfix$created(ByteBuffer buffer, int length, int count, CallbackInfo ci) {
        this.ltfix$touched = System.nanoTime();
        if (buffer != null && length >= GeometryPacker.MIN_BYTES) {
            GeometryPacker.track(this);
        }
    }

    @Inject(method = "uploaded()V", at = @At("HEAD"), cancellable = true)
    private void ltfix$keepInMemory(CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "downloaded(Ljava/nio/ByteBuffer;)V", at = @At("HEAD"), cancellable = true)
    private void ltfix$downloaded(ByteBuffer buffer, CallbackInfo ci) {
        synchronized (this) {
            this.uploaded = false;
            this.ltfix$packed = null;
            this.byteBuffer = buffer;
            this.ltfix$touched = System.nanoTime();
        }
        ci.cancel();
    }

    @Inject(method = "byteBuffer()Ljava/nio/ByteBuffer;", at = @At("HEAD"), cancellable = true)
    private void ltfix$unpacked(CallbackInfoReturnable<ByteBuffer> cir) {
        synchronized (this) {
            this.ltfix$touched = System.nanoTime();
            if (this.ltfix$packed != null) {
                try {
                    this.byteBuffer = GeometryPacker.inflate(this.ltfix$packed, this.length);
                    this.ltfix$packed = null;
                } catch (RuntimeException e) {
                    // never expected (deflate/inflate are exact); a missing tile beats a crashed chunk worker
                    cir.setReturnValue(null);
                    return;
                }
            }
            cir.setReturnValue(this.byteBuffer);
        }
    }

    @Override
    public long ltfix$touched() {
        return this.ltfix$touched;
    }

    @Override
    public void ltfix$pack(Deflater deflater, byte[] in, byte[] out) {
        ByteBuffer view;
        long touched;
        synchronized (this) {
            if (!GeometryPacker.active() || this.ltfix$packed != null || this.byteBuffer == null || this.length < GeometryPacker.MIN_BYTES) {
                return;
            }
            view = this.byteBuffer.duplicate();
            touched = this.ltfix$touched;
        }
        long t0 = System.nanoTime();
        // the bytes of a kept copy never change after the link is made, so they are read without the monitor
        byte[] packed = GeometryPacker.deflate(view, this.length, deflater, in, out);
        if (packed == null) {
            return;
        }
        synchronized (this) {
            if (this.ltfix$touched == touched && this.ltfix$packed == null && this.byteBuffer != null) {
                this.ltfix$packed = packed;
                this.byteBuffer = null;
                GeometryPacker.onPacked(this.length, packed.length, System.nanoTime() - t0);
            }
        }
    }
}
