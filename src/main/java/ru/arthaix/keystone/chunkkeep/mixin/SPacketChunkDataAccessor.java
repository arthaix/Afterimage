package ru.arthaix.keystone.chunkkeep.mixin;

import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.server.SPacketChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = SPacketChunkData.class, remap = false)
public interface SPacketChunkDataAccessor {
    @Accessor(value = "field_149284_a", remap = false)
    int chunkkeep$getX();

    @Accessor(value = "field_149284_a", remap = false)
    void chunkkeep$setX(int x);

    @Accessor(value = "field_149282_b", remap = false)
    int chunkkeep$getZ();

    @Accessor(value = "field_149282_b", remap = false)
    void chunkkeep$setZ(int z);

    @Accessor(value = "field_186948_c", remap = false)
    int chunkkeep$getSections();

    @Accessor(value = "field_186948_c", remap = false)
    void chunkkeep$setSections(int sections);

    @Accessor(value = "field_186949_d", remap = false)
    byte[] chunkkeep$getData();

    @Accessor(value = "field_186949_d", remap = false)
    void chunkkeep$setData(byte[] data);

    @Accessor(value = "field_189557_e", remap = false)
    List<NBTTagCompound> chunkkeep$getTags();

    @Accessor(value = "field_189557_e", remap = false)
    void chunkkeep$setTags(List<NBTTagCompound> tags);

    @Accessor(value = "field_149279_g", remap = false)
    boolean chunkkeep$getFull();

    @Accessor(value = "field_149279_g", remap = false)
    void chunkkeep$setFull(boolean full);
}
