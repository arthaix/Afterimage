package ru.arthaix.keystone.teunloadbatch;


/**
 * Constant-time World.loadedTileEntityList for 1.12.2 (see mixin.MixinWorld and IndexedTileEntityList).
 *
 * Vanilla removes tile entities from that ArrayList with remove(Object) / removeAll, and checks it with contains(),
 * each a scan over the whole list. With millions of Chisels & Bits / LittleTiles tile entities loaded those scans
 * froze the server for seconds (WorldEdit operations, chunk unloads). Ticking behaviour is unchanged.
 *
 * Large block edits also no longer scan World.tickableTileEntities for every replaced tile entity
 * (DeferredRemovalList) or compare every changed block of a chunk with all earlier ones before sending it
 * (MixinPlayerChunkMapEntryChanges).
 */
public class TeUnloadBatch {
    public static final String MODID = "teunloadbatch";
    public static final String VERSION = "1.3.5";
}
