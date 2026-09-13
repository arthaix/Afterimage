package ru.arthaix.keystone.chunkkeep.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import ru.arthaix.keystone.chunkkeep.Pins;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Vanilla semantics for chunks that are loaded but not watched by any player: in
 * vanilla such chunks unload, so their entities and ticking tile entities stop. With
 * pinned chunks they stay loaded, so we reproduce the "stopped" part here: entities
 * (except players) and ITickable tile entities in unwatched pinned chunks are not ticked
 * on the server. Watched chunks, and chunks other mods keep loaded (chunk-loading tickets
 * of trains, spawn chunks), tick exactly as before.
 */
@Mixin(value = World.class, remap = false)
public abstract class MixinWorldTicking {
    @Unique
    private boolean chunkkeep$unwatched(int cx, int cz) {
        Object self = this;
        if (!(self instanceof WorldServer)) {
            return false;
        }
        return Pins.isPinned(cx, cz) && !((WorldServer) self).func_184164_w().func_152621_a(cx, cz);
    }

    /** updateEntities(): this.updateEntity(entity) */
    @Redirect(method = "func_72939_s()V",
              at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;func_72870_g(Lnet/minecraft/entity/Entity;)V"))
    private void chunkkeep$updateEntity(World world, Entity entity) {
        if (!(entity instanceof EntityPlayer) && chunkkeep$unwatched(entity.field_70176_ah, entity.field_70164_aj)) {
            return;
        }
        world.func_72870_g(entity);
    }

    /** updateEntities(): ((ITickable) tileentity).update() */
    @Redirect(method = "func_72939_s()V",
              at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ITickable;func_73660_a()V"))
    private void chunkkeep$tickTileEntity(ITickable tickable) {
        if (tickable instanceof TileEntity) {
            BlockPos pos = ((TileEntity) tickable).func_174877_v();
            if (chunkkeep$unwatched(pos.func_177958_n() >> 4, pos.func_177952_p() >> 4)) {
                return;
            }
        }
        tickable.func_73660_a();
    }
}
