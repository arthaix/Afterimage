package ru.arthaix.keystone.chunkkeep.mixin;

import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.arthaix.keystone.chunkkeep.Pins;

/** queueUnload(chunk): pinned overworld chunks are never queued for unloading. */
@Mixin(value = ChunkProviderServer.class, remap = false)
public abstract class MixinChunkProviderServer {
    @Inject(method = "func_189549_a(Lnet/minecraft/world/chunk/Chunk;)V", at = @At("HEAD"), cancellable = true)
    private void chunkkeep$noUnloadForPinned(Chunk chunk, CallbackInfo ci) {
        if (chunk.func_177412_p().field_73011_w.func_186058_p().func_186068_a() == 0
                && Pins.isPinned(chunk.field_76635_g, chunk.field_76647_h)) {
            ci.cancel();
        }
    }
}
