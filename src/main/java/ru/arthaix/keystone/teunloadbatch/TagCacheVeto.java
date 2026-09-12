package ru.arthaix.keystone.teunloadbatch;

/** Implemented by mods' tile entities (through mixins) that must not have their update tag reused right now. */
public interface TagCacheVeto {
    boolean teunloadbatch$vetoTagCache();
}
