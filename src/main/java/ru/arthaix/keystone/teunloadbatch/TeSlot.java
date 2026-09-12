package ru.arthaix.keystone.teunloadbatch;

/**
 * Where a tile entity sits in its world's IndexedTileEntityList, stored on the tile entity itself (MixinTileEntitySlot),
 * so finding it needs no identity hash and no map lookup. owner is the list, gen the list's generation when the slot
 * was written: once the list is rebuilt or cleared its generation moves on and older slots read as "not in the list".
 */
public interface TeSlot {
    Object teunloadbatch$owner();

    int teunloadbatch$gen();

    int teunloadbatch$slot();

    void teunloadbatch$setSlot(Object owner, int gen, int slot);
}
