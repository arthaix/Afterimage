package ru.arthaix.keystone.irfar.umcmixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import ru.arthaix.keystone.irfar.IrFar;

/**
 * UMC entities get forceSpawn. Server: the entity tracker then sends them to players who do not watch their chunk.
 * Client: they spawn, and are spawned again after their chunk unloads, where the client has no chunk.
 */
@Mixin(targets = { "cam72cam.mod.entity.ModdedEntity", "cam72cam.mod.entity.SeatEntity" }, remap = false)
public abstract class MixinModdedEntityFar {
    @Inject(method = "<init>(Lnet/minecraft/world/World;)V", at = @At("RETURN"))
    private void irfar$forceSpawn(World world, CallbackInfo ci) {
        if (IrFar.ENABLED) {
            ((Entity) (Object) this).field_98038_p = true;
        }
    }
}
