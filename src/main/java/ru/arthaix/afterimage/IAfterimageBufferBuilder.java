package ru.arthaix.afterimage;

/** Implemented on net.minecraft.client.renderer.BufferBuilder by MixinBufferBuilder. */
public interface IAfterimageBufferBuilder {
    /** {size, exact, multiset} a chunk worker computed for the finished buffer, or null; clears it. */
    long[] afterimage$takePreHash();

    void afterimage$setPreHash(long[] hash);
}
