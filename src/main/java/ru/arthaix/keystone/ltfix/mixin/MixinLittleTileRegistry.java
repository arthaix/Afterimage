package ru.arthaix.keystone.ltfix.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.block.Block;
import ru.arthaix.keystone.ltfix.BlockNames;

/** LittleTileRegistry.loadTile (old tile format): block lookup by name through the BlockNames cache. */
@Mixin(targets = "com.creativemd.littletiles.common.tile.registry.LittleTileRegistry", remap = false)
public abstract class MixinLittleTileRegistry {
    @Redirect(method = "loadTile", at = @At(value = "INVOKE", target = "Lnet/minecraft/block/Block;func_149684_b(Ljava/lang/String;)Lnet/minecraft/block/Block;"))
    private static Block ltfix$cachedBlock(String name) {
        return BlockNames.get(name);
    }
}
