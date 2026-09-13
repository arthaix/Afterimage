package ru.arthaix.keystone.ltfix.mixin;

import java.nio.ByteBuffer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.creativemd.littletiles.client.render.cache.IRenderDataCache;

import ru.arthaix.keystone.ltfix.GeometryPacker;
import ru.arthaix.keystone.ltfix.PackableLink;

/**
 * 1. discard: a chunk upload that was replaced before it reached the GPU unlinked its tile entities from their geometry,
 *    so tiles not included in the replacing upload lost their only copy and stayed invisible. Their last geometry is
 *    still valid and is now kept; a tile entity's own re-render replaces it.
 * 2. add: tile geometry is now shared by every rebuild of its chunk, and chunk workers may rebuild the same chunk
 *    concurrently (a cancelled task still running). Each rebuild reads through its own view of the buffer, so no two
 *    threads move the same position and limit.
 */
@Mixin(targets = "com.creativemd.littletiles.client.render.cache.ChunkBlockLayerCache", remap = false)
public abstract class MixinChunkBlockLayerCache {
    @Shadow
    public abstract void reset();

    @Inject(method = "discard()V", at = @At("HEAD"), cancellable = true)
    private void ltfix$keepTileGeometry(CallbackInfo ci) {
        reset();
        ci.cancel();
    }

    @Redirect(method = "add", at = @At(value = "INVOKE", target = "Lcom/creativemd/littletiles/client/render/cache/IRenderDataCache;byteBuffer()Ljava/nio/ByteBuffer;"))
    private ByteBuffer ltfix$ownView(IRenderDataCache data) {
        ByteBuffer b = data.byteBuffer();
        if (b == null) {
            return null;
        }
        // the link BlockRenderCache makes from this view next shares the bytes and takes over their count
        if (data instanceof PackableLink) {
            GeometryPacker.offerInheritance((PackableLink) data, b);
        }
        return b.duplicate().order(b.order());
    }
}
