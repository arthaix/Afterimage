package ru.arthaix.keystone.ltfix.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.creativemd.littletiles.common.tile.LittleTile;

import net.minecraft.nbt.NBTTagList;
import ru.arthaix.keystone.ltfix.LtPreparse;

/** readTiles returns tiles parsed in the background for this exact list when LtPreparse has them. */
@Mixin(targets = "com.creativemd.littletiles.common.util.compression.LittleNBTCompressionTools", remap = false)
public abstract class MixinLittleNBTCompressionTools {
    @Inject(method = "readTiles(Lnet/minecraft/nbt/NBTTagList;)Ljava/util/List;", at = @At("HEAD"), cancellable = true, require = 0)
    private static void ltfix$preparsed(NBTTagList list, CallbackInfoReturnable<List<LittleTile>> cir) {
        List<LittleTile> tiles = LtPreparse.take(list);
        if (tiles != null) {
            cir.setReturnValue(tiles);
        }
    }
}
