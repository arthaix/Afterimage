package ru.arthaix.keystone.packetbudget.mixin;

import net.minecraft.network.play.server.SPacketMultiBlockChange;
import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = SPacketMultiBlockChange.class, remap = false)
public interface MultiBlockChangeAccessor {
    @Accessor(value = "field_148925_b", remap = false)
    ChunkPos packetbudget$getChunkPos();
}
