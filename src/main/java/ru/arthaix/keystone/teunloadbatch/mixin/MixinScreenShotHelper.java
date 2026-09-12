package ru.arthaix.keystone.teunloadbatch.mixin;

import java.awt.image.RenderedImage;
import java.io.File;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.util.ScreenShotHelper;
import ru.arthaix.keystone.teunloadbatch.Screenshots;

/** F2 encoded the PNG on the client thread (0.4-0.9 s frames at this resolution); the image is written in the background. */
@Mixin(value = ScreenShotHelper.class, remap = false)
public abstract class MixinScreenShotHelper {
    @Redirect(method = "func_148259_a", at = @At(value = "INVOKE", target = "Ljavax/imageio/ImageIO;write(Ljava/awt/image/RenderedImage;Ljava/lang/String;Ljava/io/File;)Z"), require = 0)
    private static boolean teunloadbatch$writeInBackground(RenderedImage image, String format, File file) {
        return Screenshots.write(image, format, file);
    }
}
