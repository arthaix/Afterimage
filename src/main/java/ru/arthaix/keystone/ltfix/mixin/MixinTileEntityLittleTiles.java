package ru.arthaix.keystone.ltfix.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.creativemd.littletiles.client.render.world.RenderUtils;
import com.creativemd.littletiles.common.tileentity.TileEntityLittleTiles;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;

/**
 * Client: a LittleTiles tile entity queues its geometry only from a chunk rebuild, and only the first time
 * (TileEntityRenderManager.chunkUpdate checks requestedIndex == -1). When its tile data arrives after that first rebuild
 * (tile data applied later than the chunk's blocks, or a tile entity created early by a neighbour lookup) it had been
 * rendered empty and was never rendered again: invisible until something else changed next to it. Reading tile data
 * on the client now queues a re-render. A tile entity update packet (handleUpdatePacket) queues one itself through
 * updateTiles, so reads inside it are left alone: two requests in a row made the build finish with a stale index and
 * run a second time.
 */
@Mixin(value = TileEntityLittleTiles.class, remap = false)
public abstract class MixinTileEntityLittleTiles {
    private static final boolean ltfix$ENABLED = !"false".equals(System.getProperty("ltfix.renderOnRead"));

    @Unique
    private boolean ltfix$inPacket;

    @Inject(method = "handleUpdatePacket(Lnet/minecraft/network/NetworkManager;Lnet/minecraft/nbt/NBTTagCompound;)V", at = @At("HEAD"), require = 0)
    private void ltfix$packetStart(NetworkManager net, NBTTagCompound nbt, CallbackInfo ci) {
        this.ltfix$inPacket = true;
    }

    @Inject(method = "handleUpdatePacket(Lnet/minecraft/network/NetworkManager;Lnet/minecraft/nbt/NBTTagCompound;)V", at = @At("RETURN"), require = 0)
    private void ltfix$packetEnd(NetworkManager net, NBTTagCompound nbt, CallbackInfo ci) {
        this.ltfix$inPacket = false;
    }

    @Inject(method = "func_145839_a", at = @At("RETURN"))
    private void ltfix$renderReadData(NBTTagCompound nbt, CallbackInfo ci) {
        if (!ltfix$ENABLED || this.ltfix$inPacket) {
            return;
        }
        TileEntityLittleTiles te = (TileEntityLittleTiles) (Object) this;
        // only tile entities of the real client world, and only once a view frustum exists (LittleTiles looks the
        // RenderChunk up while queueing; without one it would count the request as done and never render the tile)
        if (te.render != null && te.func_145831_w() instanceof WorldClient && RenderUtils.getViewFrustum() != null) {
            te.render.tilesChanged();
        }
    }
}
