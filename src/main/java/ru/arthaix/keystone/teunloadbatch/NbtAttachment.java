package ru.arthaix.keystone.teunloadbatch;

/** Implemented on NBTTagList by MixinNBTTagListAttachment: one object another mod attaches to this exact list. */
public interface NbtAttachment {
    Object teunloadbatch$peekAttachment();

    /** Returns the attachment and removes it (at most one reader gets it). */
    Object teunloadbatch$takeAttachment();

    void teunloadbatch$setAttachment(Object attachment);
}
