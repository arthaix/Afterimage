package ru.arthaix.keystone.ltfix.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.tileentity.TileEntity;
import ru.arthaix.keystone.teunloadbatch.TagCache;

/**
 * Chisels & Bits side of the server's chunk-packet tag cache (teunloadbatch TagCache): every method that changes a
 * chiseled block's bits, state or orientation voids the cached bytes, on top of the vanilla markDirty/readFromNBT hooks.
 */
@Mixin(targets = "mod.chiselsandbits.chiseledblock.TileEntityBlockChiseled", remap = false)
public abstract class MixinCbTagCache {
    @Inject(method = {
        "setState(Lnet/minecraftforge/common/property/IExtendedBlockState;)V",
        "setBlob(Lmod/chiselsandbits/chiseledblock/data/VoxelBlob;)V",
        "setBlob(Lmod/chiselsandbits/chiseledblock/data/VoxelBlob;Z)V",
        "completeEditOperation(Lmod/chiselsandbits/chiseledblock/data/VoxelBlob;)V",
        "fillWith(Lnet/minecraft/block/state/IBlockState;)V",
        "rotateBlock(Lnet/minecraft/util/EnumFacing;)V",
        "func_189668_a(Lnet/minecraft/util/Mirror;)V",
        "func_189667_a(Lnet/minecraft/util/Rotation;)V",
        "setNormalCube(Z)V",
        "copyFrom(Lmod/chiselsandbits/chiseledblock/TileEntityBlockChiseled;)V",
        "handleUpdateTag(Lnet/minecraft/nbt/NBTTagCompound;)V",
        "finishUpdate()V"
    }, at = @At("RETURN"), require = 0)
    private void ltfix$chiseledChanged(CallbackInfo ci) {
        TagCache.bump((TileEntity) (Object) this);
    }

    @Inject(method = {
        "updateBlob(Lmod/chiselsandbits/chiseledblock/NBTBlobConverter;Z)Z",
        "readChisleData(Lnet/minecraft/nbt/NBTTagCompound;)Z"
    }, at = @At("RETURN"), require = 0)
    private void ltfix$chiseledChangedReturning(CallbackInfoReturnable<Boolean> cir) {
        TagCache.bump((TileEntity) (Object) this);
    }
}
