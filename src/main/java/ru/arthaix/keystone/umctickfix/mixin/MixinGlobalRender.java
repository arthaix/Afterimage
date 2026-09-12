package ru.arthaix.keystone.umctickfix.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.renderer.culling.ClippingHelperImpl;

/**
 * UMC's GlobalRender.getCamera, called for every frame by EntityRenderer.renderLargeEntities, built a new
 * ClippingHelperImpl each time: three direct FloatBuffers, each with its own Cleaner for the collector to process.
 * The helper's init() (called right after) reads the current matrices again, so one instance on the client thread
 * serves every call. Vanilla's own ClippingHelperImpl.getInstance() is not shared, its state belongs to the terrain pass.
 */
@Mixin(targets = "cam72cam.mod.render.GlobalRender", remap = false)
public class MixinGlobalRender {
    @Unique
    private static ClippingHelperImpl umctickfix$clipping;

    @Redirect(method = "getCamera", at = @At(value = "NEW", target = "net/minecraft/client/renderer/culling/ClippingHelperImpl"), remap = false)
    private static ClippingHelperImpl umctickfix$reuseClipping() {
        ClippingHelperImpl c = umctickfix$clipping;
        if (c == null) {
            c = new ClippingHelperImpl();
            umctickfix$clipping = c;
        }
        return c;
    }
}
