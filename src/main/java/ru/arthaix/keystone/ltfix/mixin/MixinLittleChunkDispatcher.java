package ru.arthaix.keystone.ltfix.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.creativemd.creativecore.client.rendering.model.BufferBuilderUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.BlockRenderLayer;
import ru.arthaix.keystone.ltfix.GeometryPacker;

/**
 * LittleChunkDispatcher.uploadChunk merges a section's tiles into the chunk builder.
 * 1. On a chunk worker thread it first waits (at most 2 s) while unpacked tile geometry is over GeometryPacker's hard
 *    limit, so a flood of rebuilds cannot outrun the packers. The client thread never waits.
 * 2. Growing the chunk builder allocates direct memory. "OutOfMemoryError: Direct buffer memory" there crashed the game
 *    ("Batching chunks"); the allocation is now retried for up to 10 s while dropped buffers are collected. The failed
 *    allocation changed nothing, so a retry is safe.
 */
@Mixin(targets = "com.creativemd.littletiles.client.render.world.LittleChunkDispatcher", remap = false)
public abstract class MixinLittleChunkDispatcher {
    @Inject(method = "uploadChunk", at = @At("HEAD"))
    private static void ltfix$waitForRoom(BlockRenderLayer layer, BufferBuilder buffer, RenderChunk chunk, CompiledChunk compiled, double distance,
                                          CallbackInfo ci) {
        if (!Minecraft.func_71410_x().func_152345_ab()) {
            GeometryPacker.waitForRoom();
        }
    }

    @Redirect(method = "uploadChunk", at = @At(value = "INVOKE",
              target = "Lcom/creativemd/creativecore/client/rendering/model/BufferBuilderUtils;growBufferSmall(Lnet/minecraft/client/renderer/BufferBuilder;I)V"))
    private static void ltfix$growOrRetry(BufferBuilder buffer, int size) {
        for (int attempt = 0;; attempt++) {
            try {
                BufferBuilderUtils.growBufferSmall(buffer, size);
                return;
            } catch (OutOfMemoryError e) {
                if (attempt >= 40 || e.getMessage() == null || !e.getMessage().contains("Direct buffer memory")) {
                    throw e;
                }
                if (!GeometryPacker.onDirectShortage()) {
                    throw e;
                }
            }
        }
    }
}
