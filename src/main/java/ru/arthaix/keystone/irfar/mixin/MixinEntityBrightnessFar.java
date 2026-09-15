package ru.arthaix.keystone.irfar.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import ru.arthaix.keystone.irfar.IrFar;

/**
 * Entity.getBrightnessForRender is 0 where the client has no chunk: a UMC entity kept there would be drawn black.
 * It gets full sky light instead (the lightmap still follows day and night).
 */
@Mixin(value = Entity.class, remap = false)
public abstract class MixinEntityBrightnessFar {
    @Inject(method = "func_70070_b()I", at = @At("RETURN"), cancellable = true)
    private void irfar$skyLight(CallbackInfoReturnable<Integer> cir) {
        if (cir.getReturnValueI() != 0 || !IrFar.isFarEntity(this)) {
            return;
        }
        Entity self = (Entity) (Object) this;
        if (self.field_70170_p != null
            && !self.field_70170_p.func_175667_e(new BlockPos(self.field_70165_t, self.field_70163_u + self.func_70047_e(), self.field_70161_v))) {
            cir.setReturnValue(15 << 20);
        }
    }
}
