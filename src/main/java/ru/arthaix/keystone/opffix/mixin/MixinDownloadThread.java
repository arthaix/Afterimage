package ru.arthaix.keystone.opffix.mixin;

import java.io.IOException;

import com.creativemd.opf.client.cache.TextureCache;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * DownloadThread.run(): the call to load(url), which uses the shared date format and writes the texture cache, and
 * the cache cleanup after a failed attempt run under one lock. Decoding the image stays outside the lock.
 */
@Mixin(targets = "com.creativemd.opf.client.DownloadThread", remap = false)
public abstract class MixinDownloadThread {
    @Unique private static final Object opffix$LOCK = new Object();

    @Redirect(method = "run()V", remap = false,
              at = @At(value = "INVOKE", target = "Lcom/creativemd/opf/client/DownloadThread;load(Ljava/lang/String;)[B", remap = false))
    private byte[] opffix$serializedLoad(String url) throws IOException {
        synchronized (opffix$LOCK) {
            return com.creativemd.opf.client.DownloadThread.load(url);
        }
    }

    @Redirect(method = "run()V", remap = false,
              at = @At(value = "INVOKE", target = "Lcom/creativemd/opf/client/cache/TextureCache;deleteEntry(Ljava/lang/String;)V", remap = false))
    private void opffix$serializedDelete(TextureCache cache, String url) {
        synchronized (opffix$LOCK) {
            cache.deleteEntry(url);
        }
    }
}
