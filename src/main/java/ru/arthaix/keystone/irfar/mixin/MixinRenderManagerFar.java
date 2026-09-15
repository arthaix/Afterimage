package ru.arthaix.keystone.irfar.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import ru.arthaix.keystone.irfar.IrFar;
import ru.arthaix.keystone.irfar.IrFarClient;

/** Records which UMC entities a pass drew this frame, so IrFar's own pass draws each of the others once. */
@Mixin(value = RenderManager.class, remap = false)
public abstract class MixinRenderManagerFar {
    @Inject(method = "func_188388_a(Lnet/minecraft/entity/Entity;FZ)V", at = @At("HEAD"))
    private void irfar$markDrawn(Entity entity, float partialTicks, boolean debug, CallbackInfo ci) {
        if (IrFar.isModdedEntity(entity)) {
            IrFarClient.markDrawn(entity);
        }
    }
}
