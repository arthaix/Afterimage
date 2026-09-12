package ru.arthaix.keystone.ltfix.mixin;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import ru.arthaix.keystone.ltfix.RegionPrewarm;

/** JourneyMap RegionImageHandler.readRegionImage takes region images decoded ahead of time when they are ready. */
@Mixin(targets = "journeymap.client.io.RegionImageHandler", remap = false)
public abstract class MixinJmRegionImageHandler {
    @Redirect(method = "readRegionImage", at = @At(value = "INVOKE", target = "Ljavax/imageio/ImageIO;read(Ljava/io/File;)Ljava/awt/image/BufferedImage;"))
    private static BufferedImage ltfix$prewarmed(File file) throws IOException {
        return RegionPrewarm.read(file);
    }
}
