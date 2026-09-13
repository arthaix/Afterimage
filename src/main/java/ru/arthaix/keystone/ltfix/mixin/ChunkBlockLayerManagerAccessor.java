package ru.arthaix.keystone.ltfix.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.creativemd.littletiles.client.render.cache.ChunkBlockLayerCache;

@Mixin(targets = "com.creativemd.littletiles.client.render.cache.ChunkBlockLayerManager", remap = false)
public interface ChunkBlockLayerManagerAccessor {
    @Accessor(value = "cache", remap = false)
    void ltfix$setCache(ChunkBlockLayerCache cache);

    @Accessor(value = "uploaded", remap = false)
    void ltfix$setUploaded(ChunkBlockLayerCache uploaded);
}
