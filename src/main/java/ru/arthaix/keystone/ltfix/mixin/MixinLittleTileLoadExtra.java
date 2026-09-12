package ru.arthaix.keystone.ltfix.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import ru.arthaix.keystone.ltfix.BlockNames;

/**
 * LittleTile.loadTileExtra split the tile's "block" string, glued the name back together and looked the block up for
 * every single tile (the top frame of slow server ticks while the city loads). Common values are parsed once
 * (BlockNames.parse) and set exactly as LittleTiles would; anything unusual still goes through LittleTiles' own code.
 */
@Mixin(targets = "com.creativemd.littletiles.common.tile.LittleTile", remap = false)
public abstract class MixinLittleTileLoadExtra {
    @Shadow
    public boolean invisible;

    @Shadow
    public boolean glowing;

    @Shadow
    protected abstract void setBlock(String defaultName, Block block, int meta);

    @Inject(method = "loadTileExtra", at = @At("HEAD"), cancellable = true)
    private void ltfix$parsedBlock(NBTTagCompound nbt, CallbackInfo ci) {
        if (nbt.func_74764_b("meta")) {
            return;
        }
        String full = nbt.func_74779_i("block");
        BlockNames.Parsed p = BlockNames.parse(full);
        if (p == null) {
            return;
        }
        this.invisible = nbt.func_74767_n("invisible");
        this.glowing = nbt.func_74767_n("glowing");
        setBlock(full, p.block, p.meta);
        ci.cancel();
    }
}
