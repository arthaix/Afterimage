package ru.arthaix.afterimage.mixin;

import com.google.common.util.concurrent.ListenableFuture;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.BlockRenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.arthaix.afterimage.Capture;

/**
 * uploadChunk called on a chunk worker queues the upload for the client thread. Just before it does, the finished
 * buffer is fingerprinted on the worker (LittleTiles has merged its tiles into it at the head of the method by then),
 * so the client thread's upload hook does not hash it.
 */
@Mixin(value = ChunkRenderDispatcher.class, remap = false)
public abstract class MixinChunkRenderDispatcher {
    @Inject(method = "func_188245_a", at = @At(value = "INVOKE", target = "Lcom/google/common/util/concurrent/ListenableFutureTask;create(Ljava/lang/Runnable;Ljava/lang/Object;)Lcom/google/common/util/concurrent/ListenableFutureTask;"))
    private void afterimage$hashOnWorker(BlockRenderLayer layer, BufferBuilder buffer, RenderChunk rc, CompiledChunk cc, double distance,
                                         CallbackInfoReturnable<ListenableFuture<Object>> cir) {
        Capture.workerHash(layer, buffer);
    }
}
