package ru.arthaix.keystone.ltfix.mixin;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalListeners;

/**
 * JourneyMap writes a region's map images to PNG when the region leaves its image cache, and Guava runs that listener on
 * whichever thread touched the cache: usually the client thread drawing the minimap, 50-200 ms per region. The listener
 * now runs on one background thread. It only writes files under JourneyMap's own write lock and queues texture
 * deletion for the render thread, so it needs no GL context.
 */
@Mixin(targets = "journeymap.client.model.RegionImageCache", remap = false)
public abstract class MixinJmRegionImageCache {
    private static final ExecutorService ltfix$WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ltfix journeymap writer");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    @Redirect(method = "initRegionImageSetsCache", at = @At(value = "INVOKE", target = "Lcom/google/common/cache/CacheBuilder;removalListener(Lcom/google/common/cache/RemovalListener;)Lcom/google/common/cache/CacheBuilder;"))
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private CacheBuilder ltfix$asyncRemoval(CacheBuilder builder, RemovalListener listener) {
        return builder.removalListener(RemovalListeners.asynchronous(listener, ltfix$WRITER));
    }
}
