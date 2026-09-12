package ru.arthaix.keystone.cbbakecache;


/**
 * C&B bake cache: Chisels & Bits 14.33 re-runs ChiselLayer.filter (a full pass
 * over the 4096-voxel blob) seven times per block per render layer while baking
 * chunk geometry: once for the block itself and once for each of its six
 * neighbours. On a city built from millions of chiseled blocks that loop alone
 * eats ~85% of the chunk-batcher threads. This mod memoises the filtered blob
 * per (state reference, layer) so each blob is filtered once and then reused
 * read-only. Geometry output is identical.
 */
public class CbBakeCache {
    public static final String MODID = "cbbakecache";
    public static final String VERSION = "1.2.1";
}
