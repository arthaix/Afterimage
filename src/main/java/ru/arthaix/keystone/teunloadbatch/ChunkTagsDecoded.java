package ru.arthaix.keystone.teunloadbatch;

import java.util.List;
import java.util.function.Consumer;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Client: the tile entity tags of a chunk packet, right after the network thread decoded it and long before the client
 * thread applies them (packetbudget spreads that over frames). ltfix listens to parse LittleTiles tiles in the background.
 */
public final class ChunkTagsDecoded {
    private static volatile Consumer<List<NBTTagCompound>> listener;

    private ChunkTagsDecoded() {
    }

    public static void setListener(Consumer<List<NBTTagCompound>> l) {
        listener = l;
    }

    /** Network thread. */
    public static void fire(List<NBTTagCompound> tags) {
        Consumer<List<NBTTagCompound>> l = listener;
        if (l == null || tags == null || tags.isEmpty()) {
            return;
        }
        try {
            l.accept(tags);
        } catch (Throwable ignored) {
            // the client thread parses as before
        }
    }
}
