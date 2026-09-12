package ru.arthaix.keystone.teunloadbatch.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.chunk.Chunk;
import ru.arthaix.keystone.teunloadbatch.ChunkUnloads;

/** Chunk.onUnload: every "chunk was loaded" remembered by tile entities is void from now on (see ChunkUnloads). */
@Mixin(value = Chunk.class, remap = false)
public abstract class MixinChunkUnload {
    @Inject(method = "func_76623_d()V", at = @At("HEAD"))
    private void teunloadbatch$unloading(CallbackInfo ci) {
        ChunkUnloads.bump();
    }
}
