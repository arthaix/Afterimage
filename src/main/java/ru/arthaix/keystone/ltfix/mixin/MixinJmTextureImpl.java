package ru.arthaix.keystone.ltfix.mixin;

import java.awt.image.BufferedImage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import ru.arthaix.keystone.ltfix.FastPixels;

/** JourneyMap TextureImpl.loadByteBuffer reads the image's pixels through FastPixels. */
@Mixin(targets = "journeymap.client.render.texture.TextureImpl", remap = false)
public abstract class MixinJmTextureImpl {
    @Redirect(method = "loadByteBuffer", at = @At(value = "INVOKE", target = "Ljava/awt/image/BufferedImage;getRGB(IIII[III)[I"))
    private static int[] ltfix$fastPixels(BufferedImage img, int x, int y, int w, int h, int[] out, int off, int scan) {
        return FastPixels.getRGB(img, x, y, w, h, out, off, scan);
    }
}
