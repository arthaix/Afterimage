package ru.arthaix.keystone.chunkkeep.mixin;

import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.management.PlayerChunkMapEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = PlayerChunkMapEntry.class, remap = false)
public interface PlayerChunkMapEntryAccessor {
    @Accessor(value = "field_187283_c", remap = false)
    List<EntityPlayerMP> chunkkeep$players();
}
