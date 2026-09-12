package ru.arthaix.keystone.teunloadbatch;

/**
 * Switches and counters of the block edit speedups (DeferredRemovalList, MixinPlayerChunkMapEntryChanges). Counters are
 * plain fields written by the thread doing the edit: approximate, for the metrics line only.
 */
public final class EditStats {
    /** -Dteunloadbatch.deferTickableRemoval=false keeps World.tickableTileEntities a plain ArrayList */
    public static final boolean DEFER_TICKABLE = !"false".equals(System.getProperty("teunloadbatch.deferTickableRemoval"));
    /** -Dteunloadbatch.dedupBlockChanges=false keeps Forge's linear duplicate check in PlayerChunkMapEntry.blockChanged */
    public static final boolean DEDUP_CHANGES = !"false".equals(System.getProperty("teunloadbatch.dedupBlockChanges"));
    /** up to this many recorded changes the original linear check runs */
    public static final int LINEAR_CHANGES = 64;

    public static long tickableDeferred;
    public static long tickablePasses;
    public static long tickablePassNanos;
    public static long changeSets;
    public static long changesDeduped;

    private EditStats() {
    }

    public static String stats() {
        return "edits: tickable removals deferred " + tickableDeferred + " applied in " + tickablePasses + " passes "
            + tickablePassNanos / 1_000_000L + "ms, chunk change sets " + changeSets + " duplicates " + changesDeduped;
    }
}
