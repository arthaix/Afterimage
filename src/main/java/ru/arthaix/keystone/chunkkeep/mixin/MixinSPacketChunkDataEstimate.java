package ru.arthaix.keystone.chunkkeep.mixin;

import net.minecraft.network.play.server.SPacketChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = SPacketChunkData.class, remap = false)
public abstract class MixinSPacketChunkDataEstimate implements ChunkPacketEstimate {
    @Unique
    private long chunkkeep$estimate;

    @Override
    public long chunkkeep$estimate() {
        return this.chunkkeep$estimate;
    }

    @Override
    public void chunkkeep$setEstimate(long estimate) {
        this.chunkkeep$estimate = estimate;
    }
}
