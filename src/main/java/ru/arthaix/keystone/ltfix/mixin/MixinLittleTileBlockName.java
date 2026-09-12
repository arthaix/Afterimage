package ru.arthaix.keystone.ltfix.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.block.Block;
import ru.arthaix.keystone.ltfix.BlockNames;

/** LittleTile.loadTileExtra: the block of every loaded tile is looked up by name through the BlockNames cache. */
@Mixin(targets = "com.creativemd.littletiles.common.tile.LittleTile", remap = false)
public abstract class MixinLittleTileBlockName {
    @Redirect(method = "loadTileExtra", at = @At(value = "INVOKE", target = "Lnet/minecraft/block/Block;func_149684_b(Ljava/lang/String;)Lnet/minecraft/block/Block;"))
    private Block ltfix$cachedBlock(String name) {
        return BlockNames.get(name);
    }
}
