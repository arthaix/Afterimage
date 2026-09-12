package ru.arthaix.keystone.ltfix.mixin;

import java.util.ArrayList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.creativemd.creativecore.common.utils.type.HashMapList;
import com.creativemd.creativecore.common.world.IOrientatedWorld;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import ru.arthaix.keystone.ltfix.NeighborDedup;

/** NeighborUpdateOrganizer.add with a constant-time "already queued" check; see NeighborDedup. */
@Mixin(targets = "com.creativemd.littletiles.server.NeighborUpdateOrganizer", remap = false)
public abstract class MixinNeighborUpdateOrganizer {
    @Shadow private HashMapList<World, BlockPos> positions;

    @Unique private final NeighborDedup ltfix$dedup = new NeighborDedup();

    @Inject(method = "add(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;)V", at = @At("HEAD"), cancellable = true)
    private void ltfix$add(World world, BlockPos pos, CallbackInfo ci) {
        if (!NeighborDedup.ENABLED || world instanceof IOrientatedWorld) {
            return;
        }
        ci.cancel();
        HashMapList<World, BlockPos> map = this.positions;
        ArrayList<BlockPos> current = map.getValues(world);
        if (this.ltfix$dedup.isNew(world, current, pos)) {
            map.add(world, pos);
            this.ltfix$dedup.added(world, map.getValues(world), pos);
        }
    }
}
