package ru.arthaix.keystone.chunkkeep.mixin;

import java.util.Set;

import net.minecraft.server.management.PlayerChunkMap;
import net.minecraft.server.management.PlayerChunkMapEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = PlayerChunkMap.class, remap = false)
public interface PlayerChunkMapAccessor {
    /** dirtyEntries */
    @Accessor(value = "field_72697_d", remap = false)
    Set<PlayerChunkMapEntry> chunkkeep$dirtyEntries();
}
