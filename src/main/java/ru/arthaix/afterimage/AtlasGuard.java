package ru.arthaix.afterimage;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;

/**
 * Far copies and cache files keep block texture coordinates as positions in the block atlas. Adding or removing one
 * texture anywhere (a mod, a resource pack, a content pack's icons) moves most sprites, and a copy made against the old
 * layout then shows other textures in their place: asphalt as grass, walls as leaves.
 *
 * After every stitch of the block atlas its layout is reduced to a fingerprint. The disk cache remembers the fingerprint
 * it was written with and is discarded when it differs (Disk.checkAtlas); copies in video memory are dropped when the
 * layout changes within a session (a resource reload).
 */
public final class AtlasGuard {
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private static Field uploadedSprites;
    private static long current;

    private AtlasGuard() {
    }

    /** Client thread, TextureStitchEvent.Post of every atlas. */
    public static void onStitched(TextureMap map) {
        if (map != Minecraft.func_71410_x().func_147117_R()) {
            return;
        }
        long signature;
        try {
            signature = signature(map);
        } catch (Throwable t) {
            Capture.logError("atlas.signature", t);
            return;
        }
        long previous = current;
        current = signature;
        if (previous != 0L && previous != signature) {
            Far.clearAll();
            Capture.logInfo("atlas: the block texture layout changed, far copies dropped");
        }
        Disk.checkAtlas(signature);
    }

    /** Fingerprint of every sprite's name, position, size and texture coordinates; never 0. */
    @SuppressWarnings("unchecked")
    static long signature(TextureMap map) throws ReflectiveOperationException {
        if (uploadedSprites == null) {
            Field f = TextureMap.class.getDeclaredField("field_94252_e");
            f.setAccessible(true);
            uploadedSprites = f;
        }
        Map<String, TextureAtlasSprite> sprites = (Map<String, TextureAtlasSprite>) uploadedSprites.get(map);
        String[] names = sprites.keySet().toArray(new String[0]);
        Arrays.sort(names);
        long h = FNV_OFFSET;
        h = mix(h, names.length);
        for (String name : names) {
            TextureAtlasSprite s = sprites.get(name);
            for (int i = 0; i < name.length(); i++) {
                h = mix(h, name.charAt(i));
            }
            h = mix(h, s.func_130010_a());
            h = mix(h, s.func_110967_i());
            h = mix(h, s.func_94211_a());
            h = mix(h, s.func_94216_b());
            h = mix(h, Float.floatToIntBits(s.func_94209_e()));
            h = mix(h, Float.floatToIntBits(s.func_94212_f()));
            h = mix(h, Float.floatToIntBits(s.func_94206_g()));
            h = mix(h, Float.floatToIntBits(s.func_94210_h()));
        }
        return h == 0L ? 1L : h;
    }

    private static long mix(long h, int v) {
        for (int i = 0; i < 32; i += 8) {
            h ^= (v >>> i) & 0xFF;
            h *= FNV_PRIME;
        }
        return h;
    }
}
