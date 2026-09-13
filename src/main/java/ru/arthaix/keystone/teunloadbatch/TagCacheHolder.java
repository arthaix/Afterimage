package ru.arthaix.keystone.teunloadbatch;

/** Implemented on TileEntity by MixinTileEntityTags: the tile entity's last update tag as serialized bytes. */
public interface TagCacheHolder {
    int teunloadbatch$tagVersion();

    byte[] teunloadbatch$tagBody();

    int teunloadbatch$tagBodyVersion();

    void teunloadbatch$setTagBody(byte[] body, int version);

    /** Any change to the tile entity: the cached bytes are void. Returns the bytes that were held, or null. */
    byte[] teunloadbatch$bumpTagVersion();

    /** TagCache's bookkeeping entry for the held bytes (opaque), null when nothing is held. */
    Object teunloadbatch$tagOwner();

    void teunloadbatch$setTagOwner(Object owner);
}
