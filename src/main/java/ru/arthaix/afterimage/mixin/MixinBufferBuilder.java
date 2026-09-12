package ru.arthaix.afterimage.mixin;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.arthaix.afterimage.IAfterimageBufferBuilder;

/** Carries the fingerprint a chunk worker computed for a finished buffer to the client thread's upload of it. */
@Mixin(value = BufferBuilder.class, remap = false)
public abstract class MixinBufferBuilder implements IAfterimageBufferBuilder {
    @Unique private volatile long[] afterimage$preHash;

    @Inject(method = "func_181668_a(ILnet/minecraft/client/renderer/vertex/VertexFormat;)V", at = @At("HEAD"))
    private void afterimage$begin(int mode, VertexFormat format, CallbackInfo ci) {
        this.afterimage$preHash = null;
    }

    @Override
    public long[] afterimage$takePreHash() {
        long[] h = this.afterimage$preHash;
        this.afterimage$preHash = null;
        return h;
    }

    @Override
    public void afterimage$setPreHash(long[] hash) {
        this.afterimage$preHash = hash;
    }
}
