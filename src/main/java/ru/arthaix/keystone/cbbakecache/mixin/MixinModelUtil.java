package ru.arthaix.keystone.cbbakecache.mixin;

import java.util.HashMap;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import ru.arthaix.keystone.cbbakecache.SyncHashMap;

/**
 * Chisels &amp; Bits ModelUtil keeps the face cache, the break-particle cache and the per-face texture names in plain static
 * HashMaps filled by every chunk render worker at once. Right after the class initializes they are replaced by
 * synchronized maps; a face computed twice by two workers is harmless, a corrupted map crashed the client.
 */
@Mixin(targets = "mod.chiselsandbits.render.helpers.ModelUtil", remap = false)
public abstract class MixinModelUtil {
    @Shadow
    private static HashMap<?, ?> cache;

    @Shadow
    private static HashMap<?, ?> breakCache;

    @Shadow
    @Final
    private static HashMap<?, ?>[] blockToTexture;

    @Inject(method = "<clinit>", at = @At("RETURN"))
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void cbbakecache$threadSafeCaches(CallbackInfo ci) {
        cache = new SyncHashMap(cache);
        breakCache = new SyncHashMap(breakCache);
        for (int i = 0; i < blockToTexture.length; i++) {
            blockToTexture[i] = new SyncHashMap(blockToTexture[i]);
        }
    }
}
