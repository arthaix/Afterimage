package ru.arthaix.keystone.ltfix;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/**
 * LittleTiles 1.5.14 fixes, applied as late mixins (the LittleTiles version itself stays untouched).
 *
 * 1. LittleTile.extractNBTFromGroup deep-copied the whole tile group, including its complete list of boxes, once for
 *    every box in the group and then threw that list away: quadratic in the group size. It was 29% of server chunk
 *    loading on a dense city, and the same path runs on the client when tile data arrives. Now the group minus its
 *    boxes is copied once and each tile gets its own small copy plus its box, with the same result.
 * 2. Client: the LittleTiles rendering thread met tile entities whose data had not arrived yet, printed a stack trace
 *    and put them straight back into its queue, spinning without ever sleeping (millions of log lines). Now such a tile
 *    is retried after a short delay, silently.
 * 3. Lag metrics in ltfix-metrics.log (LtFixMetrics): client frame spikes and queues, server tick spikes.
 * 4. Freeze catcher (FreezeWatch): stack of a server tick stuck for over 5 s, written to ltfix-freeze.log.
 * 5. Stall sampler (FrameWatch): stacks and GC time of client frames over 50 ms (ltfix-hitches.log) and of dedicated
 *    server ticks over 150 ms (ltfix-ticks.log).
 * 6. Client: tile geometry is kept in memory instead of being dropped after upload and read back from the chunk VBO,
 *    which made tiles vanish or show another chunk's bytes whenever that VBO had changed, and stalled the GPU.
 * 7. Client: finished tile geometry also marks the RenderChunk that shows the tile now (renderer reloads).
 * 8. LittleTileType.createTile looks its constructor up once per class.
 * 9. Client: reading tile data queues a re-render; tile entities whose data arrived after their first chunk rebuild
 *    were rendered empty once and never again.
 * 10. Loading tiles parses and looks up their block names once per distinct name (BlockNames), both sides.
 * 11. Client: Immersive Vehicles textures not loaded yet are decoded off the client thread (AsyncTextures).
 * 12. Client: JourneyMap writes region images of evicted regions on a background thread and converts region images
 *     to texture bytes straight from their pixel arrays (FastPixels).
 * 13. Client: rendering jobs of tile entities whose chunk unloaded are dropped instead of retried forever.
 *     -Dltfix.renderOnRead=false turns off the re-render when tile data is read (item 9).
 * 14. Client: JourneyMap region images around the player are decoded ahead on a background thread (RegionPrewarm,
 *     -Dltfix.jmPrewarm=false to turn off); the client metrics line counts missing server chunks around the player.
 * 15. Server: the neighbour update queue checks for an already queued position with a hash set instead of scanning
 *     the queue (NeighborDedup); large WorldEdit edits next to LittleTiles blocks spent half their time in that scan.
 */
public class LtFix {
    public static final String MODID = "ltfix";
    public static final String VERSION = "1.5.4";

    public void preInit(FMLPreInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(new LtFixMetrics(event.getModConfigurationDirectory().getParentFile()));
        FreezeWatch.start(event.getModConfigurationDirectory().getParentFile());
        if (FMLCommonHandler.instance().getSide().isClient()) {
            FrameWatch.startClient(event.getModConfigurationDirectory().getParentFile());
            ru.arthaix.keystone.teunloadbatch.ChunkTagsDecoded.setListener(LtPreparse::submit);
        } else {
            FrameWatch.startServer(event.getModConfigurationDirectory().getParentFile());
        }
    }
}
