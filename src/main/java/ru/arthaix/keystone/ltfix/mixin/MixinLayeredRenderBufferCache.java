package ru.arthaix.keystone.ltfix.mixin;

import java.nio.ByteBuffer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.creativemd.creativecore.client.rendering.model.BufferBuilderUtils;
import com.creativemd.littletiles.client.render.cache.BufferLink;
import com.creativemd.littletiles.client.render.cache.IRenderDataCache;

import net.minecraft.client.renderer.BufferBuilder;
import ru.arthaix.keystone.ltfix.GeometryPacker;
import ru.arthaix.keystone.ltfix.PackableLink;

/**
 * A tile entity's geometry, per layer: "queue" is the newest build, "uploaded" the copy merged into the chunk.
 * 1. set(layer, builder), called by the rendering thread with the finished BufferBuilder of one tile entity, stored the
 *    builder itself: its direct buffer (one per tile per layer per build) stayed alive until the chunk rebuilt with it,
 *    and for the translucent layer, which never gets a BufferLink, for ever. Direct memory filled with them (16 GB after
 *    a flight). The bytes are now copied into a heap BufferLink at once, so every kept copy is counted and packable
 *    (GeometryPacker) and the builder can be reused (MixinRenderingThread).
 * 2. A link that is replaced by a newer one or emptied (re-render, chunk unload) gives its bytes back to the count at
 *    once instead of when the collector gets to it. All methods here are synchronized in LittleTiles.
 * 3. combine() allocated the combined geometry in direct memory; heap instead.
 */
@Mixin(targets = "com.creativemd.littletiles.client.render.cache.LayeredRenderBufferCache", remap = false)
public abstract class MixinLayeredRenderBufferCache {
    @Shadow
    private IRenderDataCache[] queue;

    @Shadow
    private BufferLink[] uploaded;

    private static void ltfix$release(Object link) {
        if (link instanceof PackableLink) {
            ((PackableLink) link).ltfix$release();
        }
    }

    @Inject(method = "set(ILnet/minecraft/client/renderer/BufferBuilder;)V", at = @At("HEAD"), cancellable = true)
    private void ltfix$setHeapCopy(int layer, BufferBuilder buffer, CallbackInfo ci) {
        if (!GeometryPacker.active()) {
            return;
        }
        ltfix$release(this.queue[layer]);
        if (buffer == null) {
            ltfix$release(this.uploaded[layer]);
            this.uploaded[layer] = null;
            this.queue[layer] = null;
            ci.cancel();
            return;
        }
        int length = BufferBuilderUtils.getBufferSizeByte(buffer);
        int count = buffer.func_178989_h();
        ByteBuffer heap = GeometryPacker.heapCopy(buffer.func_178966_f(), length);
        if (heap == buffer.func_178966_f()) {
            // heap tight or copy failed: keep the builder as LittleTiles does
            return;
        }
        this.queue[layer] = new BufferLink(heap, length, count);
        ci.cancel();
    }

    @Inject(method = "setUploaded", at = @At("HEAD"))
    private void ltfix$replaced(BufferLink link, int layer, CallbackInfo ci) {
        BufferLink old = this.uploaded[layer];
        if (old != link) {
            ltfix$release(old);
        }
        Object pending = this.queue[layer];
        if (pending != link) {
            // the build this link was merged from; the count moved to link in MixinBufferLink
            ltfix$release(pending);
        }
    }

    @Inject(method = "setEmptyIfEqual", at = @At("HEAD"))
    private void ltfix$emptiedIfEqual(BufferLink link, int layer, CallbackInfo ci) {
        if (this.uploaded[layer] == link) {
            ltfix$release(link);
        }
    }

    @Inject(method = "setEmpty", at = @At("HEAD"))
    private void ltfix$emptied(CallbackInfo ci) {
        for (int i = 0; i < this.uploaded.length; i++) {
            ltfix$release(this.uploaded[i]);
            ltfix$release(this.queue[i]);
        }
    }

    @Inject(method = "combine(Lcom/creativemd/littletiles/client/render/cache/LayeredRenderBufferCache;)V", at = @At("HEAD"))
    private void ltfix$beforeCombine(CallbackInfo ci) {
        for (int i = 0; i < this.uploaded.length; i++) {
            ltfix$release(this.uploaded[i]);
            ltfix$release(this.queue[i]);
        }
    }

    @Redirect(method = "combine(ILcom/creativemd/littletiles/client/render/cache/IRenderDataCache;Lcom/creativemd/littletiles/client/render/cache/IRenderDataCache;)Lcom/creativemd/littletiles/client/render/cache/BufferLink;",
              at = @At(value = "INVOKE", target = "Ljava/nio/ByteBuffer;allocateDirect(I)Ljava/nio/ByteBuffer;"))
    private ByteBuffer ltfix$combineOnHeap(int length) {
        return GeometryPacker.active() ? ByteBuffer.allocate(length) : ByteBuffer.allocateDirect(length);
    }
}
