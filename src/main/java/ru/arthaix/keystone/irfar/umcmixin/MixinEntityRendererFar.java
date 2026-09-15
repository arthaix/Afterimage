package ru.arthaix.keystone.irfar.umcmixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.profiler.Profiler;
import net.minecraftforge.client.MinecraftForgeClient;
import ru.arthaix.keystone.irfar.IrFar;
import ru.arthaix.keystone.irfar.IrFarClient;

/**
 * UniversalModCore's large-entity pass drew a UMC entity only when its own chunk was outside the view frustum and the
 * entity inside it. Entities whose chunk is in view but that vanilla did not draw (beyond the render distance, chunk
 * not loaded, beyond vanilla's entity range) stayed invisible. IrFarClient.drawEntities draws every one nothing drew.
 */
@Mixin(targets = "cam72cam.mod.render.EntityRenderer", remap = false)
public abstract class MixinEntityRendererFar {
    @Inject(method = "renderLargeEntities", at = @At("HEAD"), cancellable = true)
    private static void irfar$drawEntities(CallbackInfo ci) {
        if (!IrFar.ENABLED) {
            return;
        }
        ci.cancel();
        if (MinecraftForgeClient.getRenderPass() != 0) {
            return;
        }
        Minecraft mc = Minecraft.func_71410_x();
        Profiler profiler = mc.field_71424_I;
        profiler.func_76320_a("large_entity_helper");
        try {
            IrFarClient.drawEntities(mc.func_184121_ak());
        } finally {
            profiler.func_76319_b();
        }
    }
}
