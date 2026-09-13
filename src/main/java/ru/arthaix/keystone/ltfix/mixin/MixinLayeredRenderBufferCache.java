package ru.arthaix.keystone.ltfix.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.creativemd.littletiles.client.render.cache.BufferLink;

import net.minecraft.client.renderer.BufferBuilder;
import ru.arthaix.keystone.ltfix.PackableLink;

/**
 * A tile entity's geometry link that is replaced by a newer one or emptied (re-render, chunk unload) gives its bytes back
 * to GeometryPacker's count at once instead of when the collector gets to it. All four methods are synchronized.
 */
@Mixin(targets = "com.creativemd.littletiles.client.render.cache.LayeredRenderBufferCache", remap = false)
public abstract class MixinLayeredRenderBufferCache {
    @Shadow
    private BufferLink[] uploaded;

    private static void ltfix$release(BufferLink link) {
        if (link != null) {
            ((PackableLink) (Object) link).ltfix$release();
        }
    }

    @Inject(method = "setUploaded", at = @At("HEAD"))
    private void ltfix$replaced(BufferLink link, int layer, CallbackInfo ci) {
        BufferLink old = this.uploaded[layer];
        if (old != link) {
            ltfix$release(old);
        }
    }

    @Inject(method = "setEmptyIfEqual", at = @At("HEAD"))
    private void ltfix$emptiedIfEqual(BufferLink link, int layer, CallbackInfo ci) {
        if (this.uploaded[layer] == link) {
            ltfix$release(link);
        }
    }

    @Inject(method = "set(ILnet/minecraft/client/renderer/BufferBuilder;)V", at = @At("HEAD"))
    private void ltfix$cleared(int layer, BufferBuilder buffer, CallbackInfo ci) {
        if (buffer == null) {
            ltfix$release(this.uploaded[layer]);
        }
    }

    @Inject(method = "setEmpty", at = @At("HEAD"))
    private void ltfix$emptied(CallbackInfo ci) {
        for (BufferLink link : this.uploaded) {
            ltfix$release(link);
        }
    }
}
