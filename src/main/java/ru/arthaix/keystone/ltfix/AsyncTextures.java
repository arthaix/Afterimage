package ru.arthaix.keystone.ltfix;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.data.TextureMetadataSection;
import net.minecraft.util.ResourceLocation;

/**
 * Immersive Vehicles (MTS) binds its model textures through TextureManager.bindTexture, which decodes a texture it has
 * not loaded yet on the client thread (PNG inflate plus a per-pixel BufferedImage.getRGB): 50-300 ms whenever a new
 * vehicle or decoration comes into view. Such textures are now decoded on a background thread while a transparent
 * placeholder is bound, then uploaded on the client thread, at most one per frame. Loaded textures bind as before.
 */
public final class AsyncTextures {
    private static final ExecutorService DECODER = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "ltfix texture decoder");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final ConcurrentHashMap<ResourceLocation, Decoded> READY = new ConcurrentHashMap<ResourceLocation, Decoded>();
    private static final Set<ResourceLocation> PENDING = ConcurrentHashMap.newKeySet();
    private static final AtomicLong DECODED = new AtomicLong();
    private static int placeholder = -1;
    private static long uploadFrame = -1L;
    private static volatile boolean disabled;

    static final class Decoded {
        static final Decoded FAILED = new Decoded(0, 0, null, false, false);
        final int width;
        final int height;
        final int[] pixels;
        final boolean blur;
        final boolean clamp;

        Decoded(int width, int height, int[] pixels, boolean blur, boolean clamp) {
            this.width = width;
            this.height = height;
            this.pixels = pixels;
            this.blur = blur;
            this.clamp = clamp;
        }
    }

    private AsyncTextures() {
    }

    /** Client thread, instead of TextureManager.bindTexture in mcinterface1122.InterfaceRender.bindTexture. */
    public static void bind(TextureManager tm, ResourceLocation rl) {
        if (disabled || rl == null || tm.func_110581_b(rl) != null) {
            tm.func_110577_a(rl);
            return;
        }
        try {
            Decoded d = READY.get(rl);
            if (d == Decoded.FAILED) {
                // let vanilla load it and report the problem the usual way
                READY.remove(rl);
                tm.func_110577_a(rl);
                return;
            }
            if (d != null) {
                long frame = FrameWatch.clientFrame();
                if (uploadFrame != frame) {
                    uploadFrame = frame;
                    READY.remove(rl);
                    tm.func_110579_a(rl, new Preloaded(rl, d));
                    tm.func_110577_a(rl);
                    return;
                }
            } else if (PENDING.add(rl)) {
                DECODER.execute(() -> decode(rl));
            }
            GlStateManager.func_179144_i(placeholder());
        } catch (Throwable t) {
            disabled = true;
            System.err.println("[ltfix] asynchronous texture loading disabled: " + t);
            t.printStackTrace();
            tm.func_110577_a(rl);
        }
    }

    private static void decode(ResourceLocation rl) {
        Decoded d;
        try {
            d = read(Minecraft.func_71410_x().func_110442_L(), rl, true);
            DECODED.incrementAndGet();
        } catch (Throwable t) {
            d = Decoded.FAILED;
        }
        READY.put(rl, d);
        PENDING.remove(rl);
    }

    /** Same reading as SimpleTexture.loadTexture; pixels only when asked for. */
    static Decoded read(IResourceManager rm, ResourceLocation rl, boolean pixels) throws IOException {
        IResource res = rm.func_110536_a(rl);
        try {
            BufferedImage img;
            try (InputStream in = res.func_110527_b()) {
                img = TextureUtil.func_177053_a(in);
            }
            boolean blur = false;
            boolean clamp = false;
            if (res.func_110528_c()) {
                try {
                    TextureMetadataSection meta = (TextureMetadataSection) res.func_110526_a("texture");
                    if (meta != null) {
                        blur = meta.func_110479_a();
                        clamp = meta.func_110480_b();
                    }
                } catch (RuntimeException ignored) {
                    // vanilla only logs this
                }
            }
            int w = img.getWidth();
            int h = img.getHeight();
            int[] px = new int[w * h];
            img.getRGB(0, 0, w, h, px, 0, w);
            return new Decoded(w, h, px, blur, clamp);
        } finally {
            res.close();
        }
    }

    private static int placeholder() {
        if (placeholder < 0) {
            int id = TextureUtil.func_110996_a();
            TextureUtil.func_180600_a(id, 0, 1, 1);
            TextureUtil.func_147955_a(new int[][] { { 0 } }, 1, 1, 0, 0, false, false);
            placeholder = id;
        }
        return placeholder;
    }

    public static long decoded() {
        return DECODED.get();
    }

    /** A texture whose pixels were decoded in the background; a resource reload reads it again synchronously. */
    static final class Preloaded extends AbstractTexture {
        private final ResourceLocation location;
        private Decoded data;

        Preloaded(ResourceLocation location, Decoded data) {
            this.location = location;
            this.data = data;
        }

        @Override
        public void func_110551_a(IResourceManager rm) throws IOException {
            Decoded d = this.data;
            this.data = null;
            if (d == null) {
                func_147631_c();
                d = read(rm, this.location, true);
            }
            TextureUtil.func_180600_a(func_110552_b(), 0, d.width, d.height);
            TextureUtil.func_147955_a(new int[][] { d.pixels }, d.width, d.height, 0, 0, d.blur, d.clamp);
        }
    }
}
