package ru.arthaix.keystone.packetbudget;

import net.minecraftforge.fml.common.FMLLog;
import org.apache.logging.log4j.Logger;

/**
 * Client packet budget for 1.12.2.
 *
 * 1. Minecraft.runGameLoop() drains the ENTIRE scheduled-task queue every frame. That
 *    drain gets a time budget (-Dpacketbudget.ms, default 10); the remainder is processed
 *    in FIFO order on the following frames. Nothing is dropped or reordered.
 * 2. Chunk packets: the tile-entity tags of a received chunk (thousands per dense
 *    Chisels&Bits / LittleTiles chunk, ~40 ms to apply) are applied in slices of
 *    -Dpacketbudget.teMs (default 4) per frame instead of all at once, so a 300 fps
 *    client no longer freezes for ten frames per chunk. Any later packet touching that
 *    chunk applies its pending tags first.
 */
public class PacketBudget {
    public static final String MODID = "packetbudget";
    public static final String VERSION = "1.1.0";
    public static final Logger LOG = FMLLog.log;

    /** Per-frame budget for the scheduled-task drain, in nanoseconds. */
    public static final long BUDGET_NANOS = Long.getLong("packetbudget.ms", 10L) * 1_000_000L;
    /** Per-frame budget for deferred tile-entity tags, in nanoseconds. */
    public static final long TE_BUDGET_NANOS = Long.getLong("packetbudget.teMs", 4L) * 1_000_000L;
}
