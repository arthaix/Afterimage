package ru.arthaix.keystone.chunkkeep;

/** Tunables, read once from system properties. No Minecraft dependencies. */
public final class Config {
    /** Chunks farther than this (Chebyshev distance, in chunks) are released as vanilla would. */
    public static final int KEEP_RADIUS = Integer.getInteger("chunkkeep.radius", 20);
    /** Sweep kept entries every N ticks. */
    public static final int SWEEP_TICKS = Integer.getInteger("chunkkeep.sweepTicks", 20);
    /** Heap usage fraction above which the farthest half of kept chunks is released. */
    public static final double HEAP_GUARD = Integer.getInteger("chunkkeep.heapGuardPercent", 85) / 100.0;
    /** Preload pinned chunks at server start. */
    public static final boolean PRELOAD = Boolean.parseBoolean(System.getProperty("chunkkeep.preload", "true"));

    /** Time budget for chunk packets per server tick, in ms; 0 disables the budget (vanilla: up to 81 packets). */
    public static final long SEND_BUDGET_NANOS = Integer.getInteger("chunkkeep.sendBudgetMs", 15) * 1_000_000L;

    /**
     * Time budget per tick, in ms, for chunk packets a player gets the moment a chunk enters view (teleport, login,
     * fast flight); the rest is sent on following ticks, nearest first. 0 sends everything at once as vanilla does.
     */
    public static final long ENTER_BUDGET_NANOS = Integer.getInteger("chunkkeep.enterBudgetMs", 10) * 1_000_000L;

    /** Creative and spectator players are not pulled back by the "moved too quickly" check. */
    public static final boolean SKIP_SPEED_CHECK_FOR_BUILDERS = Boolean.parseBoolean(System.getProperty("chunkkeep.skipSpeedCheckForBuilders", "true"));

    private Config() {
    }
}
