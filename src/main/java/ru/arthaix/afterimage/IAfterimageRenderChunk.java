package ru.arthaix.afterimage;

/** Implemented on net.minecraft.client.renderer.chunk.RenderChunk by MixinRenderChunk. */
public interface IAfterimageRenderChunk {
    /** Incremented whenever the world (not the verifier) marks this section dirty, or it moves. */
    int afterimage$dirtyGen();
}
