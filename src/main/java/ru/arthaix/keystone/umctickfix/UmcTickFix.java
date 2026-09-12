package ru.arthaix.keystone.umctickfix;


/**
 * UMC tick fix: UniversalModCore's BlockRender copies and filters the whole
 * client loadedTileEntityList on every client tick (twice: pre and post phase)
 * to feed RenderGlobal's global TESR list. On a world with hundreds of thousands
 * of Chisels&Bits / LittleTiles tile entities that scan alone costs ~16% of the
 * client thread. 1.1.x tracks UMC tile entities incrementally instead of scanning (see MixinBlockRender); 1.1.1
 * replaces 1.1.0's iterator removals on an identity set, which froze the client for seconds after teleports.
 */
public class UmcTickFix {
    public static final String MODID = "umctickfix";
    public static final String VERSION = "1.1.2";
}
