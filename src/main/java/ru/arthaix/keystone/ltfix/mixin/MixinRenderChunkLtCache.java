package ru.arthaix.keystone.ltfix.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.creativemd.littletiles.client.render.cache.ChunkBlockLayerManager;

import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.util.math.BlockPos;
import ru.arthaix.keystone.ltfix.GeometryPacker;

/**
 * LittleTiles attaches a ChunkBlockLayerManager to each RenderChunk's VertexBuffer; it holds the list of tile geometry
 * merged into that buffer. When the RenderChunk moves to a section without LittleTiles (LittleChunkDispatcher.uploadChunk
 * returns before manager.set) the manager kept the previous section's list, and with it the geometry of tile entities
 * that had already unloaded, for as long as the slot showed sections without tiles. Moving or deleting the RenderChunk
 * now empties its managers.
 */
@Mixin(value = RenderChunk.class, remap = false)
public abstract class MixinRenderChunkLtCache {
    @Shadow
    public abstract VertexBuffer func_178565_b(int layer);

    @Shadow
    public abstract BlockPos func_178568_j();

    @Inject(method = "func_189562_a(III)V", at = @At("HEAD"))
    private void ltfix$forgetOnMove(int x, int y, int z, CallbackInfo ci) {
        BlockPos p = func_178568_j();
        if (p.func_177958_n() == x && p.func_177956_o() == y && p.func_177952_p() == z) {
            return;
        }
        ltfix$forget();
    }

    @Inject(method = "func_178566_a()V", at = @At("HEAD"))
    private void ltfix$forgetOnDelete(CallbackInfo ci) {
        ltfix$forget();
    }

    private void ltfix$forget() {
        if (!GeometryPacker.active()) {
            return;
        }
        for (int i = 0; i < 4; i++) {
            VertexBuffer vb;
            try {
                vb = func_178565_b(i);
            } catch (RuntimeException e) {
                return;
            }
            if (vb == null) {
                continue;
            }
            Object manager;
            try {
                manager = ChunkBlockLayerManager.blockLayerManager.get(vb);
            } catch (Throwable t) {
                return;
            }
            if (manager instanceof ChunkBlockLayerManagerAccessor) {
                synchronized (manager) {
                    ((ChunkBlockLayerManagerAccessor) manager).ltfix$setCache(null);
                    ((ChunkBlockLayerManagerAccessor) manager).ltfix$setUploaded(null);
                }
            }
        }
    }
}
