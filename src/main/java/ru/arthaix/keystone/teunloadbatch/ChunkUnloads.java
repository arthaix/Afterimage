package ru.arthaix.keystone.teunloadbatch;

/**
 * A counter bumped whenever any chunk unloads. World.updateEntities checked isBlockLoaded for every tickable tile entity
 * on every tick: one hash lookup each, 54% of the server's long ticks with the whole city loaded (cap3 JFR). A tile
 * entity whose chunk was loaded at the current generation cannot have lost it, since chunks only unload between those
 * checks by bumping the counter; so the lookup is skipped until the next unload anywhere.
 */
public final class ChunkUnloads {
    private static volatile int generation = 1;

    private ChunkUnloads() {
    }

    public static int generation() {
        return generation;
    }

    public static void bump() {
        generation++;
    }
}
