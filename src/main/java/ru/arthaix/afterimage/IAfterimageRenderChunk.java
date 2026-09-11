package ru.arthaix.afterimage;

/** Implemented on net.minecraft.client.renderer.chunk.RenderChunk by MixinRenderChunk. */
public interface IAfterimageRenderChunk {
    /** Incremented whenever the world (not the verifier) marks this section dirty, or it moves. */
    int afterimage$dirtyGen();

    /** Far-zone frame in which vanilla must not draw this section (its copy is shown while vanilla assembles it). */
    int afterimage$hideFrame();

    void afterimage$setHideFrame(int frame);
}
