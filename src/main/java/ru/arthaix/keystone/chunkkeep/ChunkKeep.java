package ru.arthaix.keystone.chunkkeep;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ThreadPoolExecutor;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.Logger;

/**
 * Chunk keep for 1.12.2 servers.
 *
 * 1. Keep radius: chunks a player has already received stay registered to that player
 *    while they remain within Config.KEEP_RADIUS chunks, so leaving and re-entering an
 *    area neither unloads nor re-sends chunks.
 * 2. Pins: config/chunkkeep-pins.txt lists overworld chunks that are loaded after server
 *    start and never unloaded. Entities and ticking tile entities in pinned chunks that no
 *    player watches are frozen, exactly as if the chunk were unloaded in vanilla.
 * 3. Preload (1.3.0): pinned chunks are loaded through Forge async chunk IO. Reading,
 *    decompressing and parsing region data runs on several IO threads; only the part
 *    Minecraft must do on the server thread (creating tile entities and entities) remains.
 *    The number of chunks in flight follows the measured tick time: halved when a tick took
 *    longer than 40 ms, raised by one while ticks stay under 25 ms.
 * 4. Creative and spectator players are not pulled back by "moved too quickly" (MixinMovementCheck).
 *
 * Minecraft methods are called by their SRG names because this class runs in the
 * obfuscated runtime. Forge additions that the SRG compile jar does not contain are
 * reached by reflection.
 */
public class ChunkKeep {
    public static final String MODID = "chunkkeep";
    public static final String VERSION = "1.4.0";
    private static final Logger LOG = FMLLog.log;

    /** Main-thread time per tick for handing chunks to the loader (and for loading them when async IO is unavailable). */
    private static final long SUBMIT_NANOS = Long.getLong("chunkkeep.preloadMs", 10L) * 1_000_000L;
    private static final long TICK_HIGH_NANOS = Long.getLong("chunkkeep.preloadTickHighMs", 40L) * 1_000_000L;
    private static final long TICK_LOW_NANOS = Long.getLong("chunkkeep.preloadTickLowMs", 25L) * 1_000_000L;
    private static final int MAX_IN_FLIGHT = Integer.getInteger("chunkkeep.preloadMaxInFlight", 32);
    private static final int IO_THREADS = Integer.getInteger("chunkkeep.preloadIoThreads",
            Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() / 3)));
    /** Stop waiting for outstanding async loads this many ticks after the last chunk was queued. */
    private static final int FINISH_TIMEOUT_TICKS = 1200;

    private File pinsFile;
    private MinecraftServer server;
    private int[][] toLoad;
    private int cursor;
    private int inFlight;
    private int done;
    private int window = 2;
    private int lastLogged;
    private int waitTicks;
    private long startedAt;
    private Method loadAsync;
    private ThreadPoolExecutor ioPool;

    public void preInit(FMLPreInitializationEvent event) {
        pinsFile = new File(event.getModConfigurationDirectory(), "chunkkeep-pins.txt");
        MinecraftForge.EVENT_BUS.register(this);
    }

    public void serverStarting(FMLServerStartingEvent event) {
        try {
            int n = Pins.load(pinsFile);
            LOG.info("[chunkkeep] keep radius {} chunks; {} pinned chunks from {}", Config.KEEP_RADIUS, n, pinsFile.getName());
        } catch (Exception e) {
            LOG.error("[chunkkeep] could not read " + pinsFile, e);
            return;
        }
        if (Config.PRELOAD && Pins.count() > 0) {
            server = event.getServer();
            toLoad = Pins.list();
            cursor = 0;
            inFlight = 0;
            done = 0;
            window = 2;
            lastLogged = 0;
            waitTicks = 0;
            startedAt = System.currentTimeMillis();
            loadAsync = findLoadAsync();
            ioPool = findIoPool();
            LOG.info("[chunkkeep] preloading {} city chunks ({}, {} IO threads, paced by tick time)", toLoad.length,
                    loadAsync != null ? "async IO" : "sync", ioPool != null ? IO_THREADS : 1);
        }
    }

    public void serverStopping(FMLServerStoppingEvent event) {
        restoreIoPool();
        toLoad = null;
        server = null;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || toLoad == null || server == null) {
            return;
        }
        WorldServer world = server.func_71218_a(0);
        if (world == null) {
            return;
        }
        ChunkProviderServer provider = world.func_72863_F();
        // duration of the tick that is ending now (vanilla stores it before the post-tick event)
        long tickNanos = server.field_71311_j[server.func_71259_af() % 100];
        if (tickNanos > TICK_HIGH_NANOS) {
            window = Math.max(1, window / 2);
        } else if (tickNanos < TICK_LOW_NANOS && inFlight >= window) {
            window = Math.min(MAX_IN_FLIGHT, window + 1);
        }
        if (ioPool != null && server.func_71259_af() % 20 == 0) {
            widenIoPool(); // Forge shrinks the pool again whenever a player joins or leaves
        }

        long deadline = System.nanoTime() + SUBMIT_NANOS;
        while (cursor < toLoad.length && inFlight < window && System.nanoTime() < deadline) {
            int[] p = toLoad[cursor++];
            if (provider.func_186026_b(p[0], p[1]) != null) {
                done++;
                continue;
            }
            if (loadAsync == null) {
                provider.func_186025_d(p[0], p[1]);
                done++;
                if (tickNanos > TICK_HIGH_NANOS) {
                    break;
                }
                continue;
            }
            inFlight++;
            try {
                loadAsync.invoke(provider, p[0], p[1], (Runnable) this::onChunkLoaded);
            } catch (Exception e) {
                inFlight--;
                loadAsync = null;
                LOG.warn("[chunkkeep] async chunk loading is not available, preloading synchronously: {}", e.toString());
                provider.func_186025_d(p[0], p[1]);
                done++;
            }
        }

        if (done / 2000 != lastLogged / 2000) {
            lastLogged = done;
            LOG.info("[chunkkeep] preloading city: {}/{} chunks ({} s, {} in flight)", done, toLoad.length,
                    (System.currentTimeMillis() - startedAt) / 1000, inFlight);
        }
        if (cursor >= toLoad.length && (inFlight <= 0 || ++waitTicks > FINISH_TIMEOUT_TICKS)) {
            LOG.info("[chunkkeep] city preload finished: {} chunks in {} s", done, (System.currentTimeMillis() - startedAt) / 1000);
            restoreIoPool();
            toLoad = null;
        }
    }

    /** Forge runs this on the server thread once the chunk is in the world. */
    private void onChunkLoaded() {
        inFlight--;
        done++;
    }

    /** Forge ChunkProviderServer.loadChunk(int, int, Runnable): async for chunks that exist on disk. */
    private static Method findLoadAsync() {
        try {
            return ChunkProviderServer.class.getMethod("loadChunk", int.class, int.class, Runnable.class);
        } catch (Throwable t) {
            return null;
        }
    }

    private static ThreadPoolExecutor findIoPool() {
        try {
            Class<?> c = Class.forName("net.minecraftforge.common.chunkio.ChunkIOExecutor");
            Field f = c.getDeclaredField("pool");
            f.setAccessible(true);
            return (ThreadPoolExecutor) f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private void widenIoPool() {
        try {
            if (ioPool.getMaximumPoolSize() < IO_THREADS) {
                ioPool.setMaximumPoolSize(IO_THREADS);
            }
            if (ioPool.getCorePoolSize() < IO_THREADS) {
                ioPool.setCorePoolSize(IO_THREADS);
            }
        } catch (RuntimeException e) {
            ioPool = null;
        }
    }

    /** Back to Forge sizing (one IO thread per 50 players). */
    private void restoreIoPool() {
        if (ioPool == null || server == null) {
            return;
        }
        try {
            Class<?> c = Class.forName("net.minecraftforge.common.chunkio.ChunkIOExecutor");
            c.getMethod("adjustPoolSize", int.class).invoke(null, server.func_184103_al().func_72394_k());
        } catch (Throwable ignored) {
            // the pool keeps its larger size; idle threads cost nothing
        }
    }
}
