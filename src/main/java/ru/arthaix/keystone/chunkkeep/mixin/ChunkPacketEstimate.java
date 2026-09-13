package ru.arthaix.keystone.chunkkeep.mixin;

/** Implemented on SPacketChunkData by MixinSPacketChunkDataEstimate: the bytes SendBacklog counted it for. */
public interface ChunkPacketEstimate {
    long chunkkeep$estimate();

    void chunkkeep$setEstimate(long estimate);
}
