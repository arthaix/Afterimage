package ru.arthaix.afterimage;

import net.minecraft.client.renderer.chunk.RenderChunk;

/** Implemented on net.minecraft.client.renderer.vertex.VertexBuffer by MixinVertexBuffer. */
public interface IAfterimageVbo {
    RenderChunk afterimage$owner();

    int afterimage$layer();

    void afterimage$setOwner(RenderChunk owner, int layer);

    int afterimage$lastSize();

    void afterimage$setLastSize(int size);

    int afterimage$glId();

    int afterimage$vertexSize();
}
