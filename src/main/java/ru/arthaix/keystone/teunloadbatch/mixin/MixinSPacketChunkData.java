package ru.arthaix.keystone.teunloadbatch.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.tileentity.TileEntity;
import ru.arthaix.keystone.teunloadbatch.TagCache;

/** Chunk packets take tile entity update tags through TagCache (reused bytes for unchanged tile entities). */
@Mixin(value = SPacketChunkData.class, remap = false)
public abstract class MixinSPacketChunkData {
    @Redirect(method = "<init>(Lnet/minecraft/world/chunk/Chunk;I)V",
              at = @At(value = "INVOKE", target = "Lnet/minecraft/tileentity/TileEntity;func_189517_E_()Lnet/minecraft/nbt/NBTTagCompound;"),
              require = 0)
    private NBTTagCompound teunloadbatch$cachedUpdateTag(TileEntity te) {
        return TagCache.updateTag(te);
    }
}
