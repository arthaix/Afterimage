package ru.arthaix.keystone.teunloadbatch.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import net.minecraft.tileentity.TileEntity;
import ru.arthaix.keystone.teunloadbatch.TeLoaded;
import ru.arthaix.keystone.teunloadbatch.TeSlot;

/**
 * Gives every tile entity the three fields IndexedTileEntityList keeps its position in, and the chunk-unload generation
 * at which its chunk was last seen loaded (MixinWorldTickLoaded).
 */
@Mixin(value = TileEntity.class, remap = false)
public abstract class MixinTileEntitySlot implements TeSlot, TeLoaded {
    @Unique
    private Object teunloadbatch$owner;

    @Unique
    private int teunloadbatch$gen;

    @Unique
    private int teunloadbatch$slot;

    @Unique
    private int teunloadbatch$loadedGen;

    @Override
    public Object teunloadbatch$owner() {
        return this.teunloadbatch$owner;
    }

    @Override
    public int teunloadbatch$gen() {
        return this.teunloadbatch$gen;
    }

    @Override
    public int teunloadbatch$slot() {
        return this.teunloadbatch$slot;
    }

    @Override
    public void teunloadbatch$setSlot(Object owner, int gen, int slot) {
        this.teunloadbatch$owner = owner;
        this.teunloadbatch$gen = gen;
        this.teunloadbatch$slot = slot;
    }

    @Override
    public int teunloadbatch$loadedGen() {
        return this.teunloadbatch$loadedGen;
    }

    @Override
    public void teunloadbatch$setLoadedGen(int gen) {
        this.teunloadbatch$loadedGen = gen;
    }
}
