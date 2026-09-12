package ru.arthaix.keystone.ltfix.mixin;

import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.arthaix.keystone.ltfix.NbtGroups;

/** LittleTile.extractNBTFromGroup: same list of per-tile compounds without copying every box once per box. */
@Mixin(targets = "com.creativemd.littletiles.common.tile.LittleTile", remap = false)
public abstract class MixinLittleTile {
    @Inject(method = "extractNBTFromGroup(Lnet/minecraft/nbt/NBTTagCompound;)Ljava/util/List;", at = @At("HEAD"), cancellable = true)
    private void ltfix$splitGroup(NBTTagCompound group, CallbackInfoReturnable<List<NBTTagCompound>> cir) {
        cir.setReturnValue(NbtGroups.split(group));
    }
}
