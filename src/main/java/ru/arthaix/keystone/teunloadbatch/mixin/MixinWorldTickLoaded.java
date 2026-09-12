package ru.arthaix.keystone.teunloadbatch.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import ru.arthaix.keystone.teunloadbatch.ChunkUnloads;
import ru.arthaix.keystone.teunloadbatch.TeLoaded;

/**
 * World.updateEntities ticks every tickable tile entity after isBlockLoaded(te.getPos()). A tile entity remembers the
 * chunk-unload generation at which its chunk was loaded; while no chunk has unloaded since, the lookup is skipped
 * (ChunkUnloads). Chunks that were not loaded are always looked up again.
 */
@Mixin(value = World.class, remap = false)
public abstract class MixinWorldTickLoaded {
    @Unique
    private TileEntity teunloadbatch$tickingTe;

    @Redirect(method = "func_72939_s", at = @At(value = "INVOKE", target = "Lnet/minecraft/tileentity/TileEntity;func_174877_v()Lnet/minecraft/util/math/BlockPos;", ordinal = 0), require = 0)
    private BlockPos teunloadbatch$tickingPos(TileEntity te) {
        this.teunloadbatch$tickingTe = te;
        return te.func_174877_v();
    }

    @Redirect(method = "func_72939_s", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;func_175667_e(Lnet/minecraft/util/math/BlockPos;)Z", ordinal = 0), require = 0)
    private boolean teunloadbatch$tickingLoaded(World world, BlockPos pos) {
        TileEntity te = this.teunloadbatch$tickingTe;
        this.teunloadbatch$tickingTe = null;
        if (te == null || te.func_174877_v() != pos) {
            return world.func_175667_e(pos);
        }
        TeLoaded loaded = (TeLoaded) te;
        int gen = ChunkUnloads.generation();
        if (loaded.teunloadbatch$loadedGen() == gen) {
            return true;
        }
        boolean result = world.func_175667_e(pos);
        if (result) {
            loaded.teunloadbatch$setLoadedGen(gen);
        }
        return result;
    }
}
