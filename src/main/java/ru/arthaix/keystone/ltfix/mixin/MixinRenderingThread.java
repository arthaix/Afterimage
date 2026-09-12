package ru.arthaix.keystone.ltfix.mixin;

import java.util.concurrent.ConcurrentLinkedQueue;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.arthaix.keystone.ltfix.RenderChunks;
import ru.arthaix.keystone.ltfix.RenderRetry;

/**
 * RenderingThread.run: the outer "catch (Exception e) { e.printStackTrace(); updateCoords.add(data); }" is what spun on
 * tiles without data. Its printStackTrace (the second Exception.printStackTrace call in run) now marks the job for a
 * delayed retry, and the following queue add schedules it instead of re-adding it at once.
 */
@Mixin(targets = "com.creativemd.littletiles.client.render.cache.RenderingThread", remap = false)
public abstract class MixinRenderingThread {
    @Redirect(method = "run()V", at = @At(value = "INVOKE", target = "Ljava/lang/Exception;printStackTrace()V", ordinal = 1))
    private void ltfix$quietNotLoaded(Exception e) {
        RenderRetry.onException(e);
    }

    @Redirect(method = "run()V", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/ConcurrentLinkedQueue;add(Ljava/lang/Object;)Z"))
    @SuppressWarnings("rawtypes")
    private boolean ltfix$requeue(ConcurrentLinkedQueue queue, Object data) {
        return RenderRetry.requeue(queue, data);
    }

    /** RenderingData is a private class of LittleTiles, hence Object. */
    @Inject(method = "finish", at = @At("RETURN"))
    private static void ltfix$markCurrentChunk(@Coerce Object data, int renderState, boolean force, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            RenderChunks.afterFinish(data);
        }
    }
}
