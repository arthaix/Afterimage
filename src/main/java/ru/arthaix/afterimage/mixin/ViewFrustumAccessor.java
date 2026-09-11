package ru.arthaix.afterimage.mixin;

import net.minecraft.client.renderer.ViewFrustum;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes ViewFrustum.getRenderChunk(BlockPos), which is protected. */
@Mixin(value = ViewFrustum.class, remap = false)
public interface ViewFrustumAccessor {
    @Invoker(value = "func_178161_a", remap = false)
    RenderChunk afterimage$getRenderChunk(BlockPos pos);
}
