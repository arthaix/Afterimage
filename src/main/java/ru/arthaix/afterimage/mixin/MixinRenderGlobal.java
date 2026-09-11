package ru.arthaix.afterimage.mixin;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.ViewFrustum;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockRenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.arthaix.afterimage.Far;

/** Draws the far zone right before vanilla's SOLID terrain layer. */
@Mixin(value = RenderGlobal.class, remap = false)
public abstract class MixinRenderGlobal {
    @Shadow private ViewFrustum field_175008_n;

    @Inject(method = "func_174977_a(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I", at = @At("HEAD"))
    private void afterimage$farPass(BlockRenderLayer layer, double partialTicks, int pass, Entity viewer,
                                CallbackInfoReturnable<Integer> cir) {
        Far.render(layer, partialTicks, viewer, this.field_175008_n);
    }
}
