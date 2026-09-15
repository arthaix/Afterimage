package ru.arthaix.keystone.irfar.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityTrackerEntry;
import net.minecraft.entity.player.EntityPlayerMP;
import ru.arthaix.keystone.irfar.IrFar;

/**
 * EntityTrackerEntry.isVisibleTo: a UMC entity (Immersive Railroading rolling stock) is visible to a player within
 * IrFar's range for that player instead of min(tracking range, view-distance * 16 - 16). The chunk-watch condition is
 * skipped for these entities by forceSpawn (MixinModdedEntityFar).
 */
@Mixin(value = EntityTrackerEntry.class, remap = false)
public abstract class MixinEntityTrackerEntryFar {
    @Shadow
    @Final
    private Entity field_73132_a;

    @Inject(method = "func_180233_c(Lnet/minecraft/entity/player/EntityPlayerMP;)Z", at = @At("HEAD"), cancellable = true)
    private void irfar$farRange(EntityPlayerMP player, CallbackInfoReturnable<Boolean> cir) {
        Entity e = this.field_73132_a;
        if (!IrFar.isFarEntity(e)) {
            return;
        }
        int r = IrFar.serverRange(player.func_110124_au());
        double dx = player.field_70165_t - e.field_70165_t;
        double dz = player.field_70161_v - e.field_70161_v;
        cir.setReturnValue(dx >= -r && dx <= r && dz >= -r && dz <= r && e.func_174827_a(player));
    }
}
