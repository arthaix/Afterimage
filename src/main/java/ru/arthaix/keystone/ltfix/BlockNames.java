package ru.arthaix.keystone.ltfix;

import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.block.Block;

/**
 * Block.getBlockFromName for LittleTiles tile loading. Every tile stores its block by name and LittleTiles resolved it
 * once per tile (a new ResourceLocation with lower-casing and a registry lookup), gigabytes of garbage while a city
 * loads. Names resolve to the same Block object for the whole game, so hits are cached; unknown names and numeric ids
 * (which a server join may remap) always go to the registry.
 */
public final class BlockNames {
    private static final int MAX = 1 << 16;
    private static final ConcurrentHashMap<String, Block> CACHE = new ConcurrentHashMap<String, Block>();

    private BlockNames() {
    }

    public static Block get(String name) {
        if (name == null) {
            return Block.func_149684_b(name);
        }
        Block b = CACHE.get(name);
        if (b != null) {
            return b;
        }
        b = Block.func_149684_b(name);
        if (b != null && CACHE.size() < MAX && !isNumeric(name)) {
            CACHE.put(name, b);
        }
        return b;
    }

    /** A tile's "block" value of the form name or name:meta, resolved. */
    public static final class Parsed {
        public final Block block;
        public final int meta;

        Parsed(Block block, int meta) {
            this.block = block;
            this.meta = meta;
        }
    }

    private static final ConcurrentHashMap<String, Parsed> PARSED = new ConcurrentHashMap<String, Parsed>();

    /**
     * What LittleTile.loadTileExtra computes for a tag without "meta" (split on ':', block parts[0]:parts[1], meta
     * parts[2] or 0), or null for anything unusual (other part counts, bad numbers, unknown blocks) so that LittleTiles'
     * own code handles it exactly as before.
     */
    public static Parsed parse(String full) {
        if (full == null) {
            return null;
        }
        Parsed p = PARSED.get(full);
        if (p != null) {
            return p;
        }
        int a = full.indexOf(':');
        if (a <= 0 || a == full.length() - 1) {
            return null;
        }
        int b = full.indexOf(':', a + 1);
        String name;
        int meta;
        if (b < 0) {
            name = full;
            meta = 0;
        } else {
            if (b == a + 1 || b == full.length() - 1 || full.indexOf(':', b + 1) >= 0) {
                return null;
            }
            name = full.substring(0, b);
            try {
                meta = Integer.parseInt(full.substring(b + 1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        Block block = get(name);
        if (block == null) {
            return null;
        }
        p = new Parsed(block, meta);
        if (PARSED.size() < MAX) {
            PARSED.put(full, p);
        }
        return p;
    }

    private static boolean isNumeric(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c < '0' || c > '9') && !(i == 0 && c == '-')) {
                return false;
            }
        }
        return true;
    }
}
