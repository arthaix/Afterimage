package ru.arthaix.keystone.teunloadbatch;

/** Implemented on TileEntity by MixinTileEntitySlot: the chunk-unload generation at which its chunk was last seen loaded. */
public interface TeLoaded {
    int teunloadbatch$loadedGen();

    void teunloadbatch$setLoadedGen(int gen);
}
