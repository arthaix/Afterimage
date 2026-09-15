package ru.arthaix.keystone.irfar;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immersive Railroading and other UniversalModCore content (trains, rails, multiblocks) stays in sight up to FACTOR
 * times the render distance each player set, instead of vanishing close by.
 *
 * Server: vanilla sends an entity to a player only within min(tracking range, view-distance * 16 - 16) blocks, 80 at
 * view-distance 6, and only while the player watches the entity's chunk. UMC entities are tracked per player up to
 * FACTOR x that player's render distance (from the client settings packet), whether or not the chunk is watched.
 *
 * Client: UMC entities are kept and updated while their chunk is not loaded on the client, lit by the sky there, and
 * drawn by a pass of their own when vanilla did not draw them (no render section, chunk not loaded, beyond vanilla's
 * entity range). UMC tile entities of chunks the client unloads are kept and drawn within the range until the chunk
 * is loaded again.
 *
 * -Dirfar.enabled=false turns it off; -Dirfar.factor (1.5) and -Dirfar.maxBlocks (1024) set the range.
 */
public final class IrFar {
    public static final boolean ENABLED = !"false".equals(System.getProperty("irfar.enabled"));
    public static final double FACTOR = parseDouble("irfar.factor", 1.5);
    public static final int MAX_BLOCKS = Integer.getInteger("irfar.maxBlocks", 1024);
    /** until a player's client settings arrive */
    private static final int DEFAULT_VIEW_CHUNKS = 12;

    private static final ClassValue<Boolean> ENTITY = new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return extendsAny(type, "cam72cam.mod.entity.ModdedEntity", "cam72cam.mod.entity.SeatEntity");
        }
    };
    private static final ClassValue<Boolean> MODDED = new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return extendsAny(type, "cam72cam.mod.entity.ModdedEntity");
        }
    };
    private static final ClassValue<Boolean> TILE = new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return extendsAny(type, "cam72cam.mod.block.tile.TileEntity");
        }
    };

    /** render distance in chunks per player */
    private static final Map<UUID, Integer> VIEW = new ConcurrentHashMap<UUID, Integer>();

    private IrFar() {
    }

    /** A UMC entity: rolling stock and the seats riding it. */
    public static boolean isFarEntity(Object entity) {
        return ENABLED && entity != null && ENTITY.get(entity.getClass());
    }

    /** A UMC entity with a model of its own (not a seat). */
    public static boolean isModdedEntity(Object entity) {
        return ENABLED && entity != null && MODDED.get(entity.getClass());
    }

    public static boolean isFarTile(Object tile) {
        return ENABLED && tile != null && TILE.get(tile.getClass());
    }

    /** FACTOR x render distance in blocks, at least 64 and at most MAX_BLOCKS. */
    public static int rangeBlocks(int viewChunks) {
        long r = Math.round(FACTOR * Math.max(2, viewChunks) * 16.0);
        return (int) Math.max(64L, Math.min((long) Math.max(64, MAX_BLOCKS), r));
    }

    /** Server, network or main thread: the render distance a player's client reported. */
    public static void setViewChunks(UUID player, int chunks) {
        if (player != null && chunks > 0) {
            VIEW.put(player, chunks);
        }
    }

    public static int serverRange(UUID player) {
        Integer v = player == null ? null : VIEW.get(player);
        return rangeBlocks(v == null ? DEFAULT_VIEW_CHUNKS : v);
    }

    static boolean extendsAny(Class<?> type, String... names) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            String name = c.getName();
            for (String n : names) {
                if (n.equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static double parseDouble(String key, double fallback) {
        try {
            String v = System.getProperty(key);
            return v == null ? fallback : Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
