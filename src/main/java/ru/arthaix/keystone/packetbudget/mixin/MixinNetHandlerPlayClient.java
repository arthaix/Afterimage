package ru.arthaix.keystone.packetbudget.mixin;

import java.util.Collections;
import java.util.List;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.server.SPacketBlockChange;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.network.play.server.SPacketMultiBlockChange;
import net.minecraft.network.play.server.SPacketUnloadChunk;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.arthaix.keystone.packetbudget.DeferredTiles;

/**
 * Chunk packet: block data is applied as usual, the tile-entity tag list is handed to
 * DeferredTiles instead of being applied in the handler. Every other packet that can
 * touch a chunk flushes that chunk's pending tags first. All hooks sit after the
 * thread check, so they only ever run on the client thread.
 */
@Mixin(value = NetHandlerPlayClient.class, remap = false)
public abstract class MixinNetHandlerPlayClient {
    private static final String THREAD_CHECK = "Lnet/minecraft/network/PacketThreadUtil;func_180031_a(Lnet/minecraft/network/Packet;Lnet/minecraft/network/INetHandler;Lnet/minecraft/util/IThreadListener;)V";

    @Shadow private WorldClient field_147300_g;

    /**
     * handleChunkData: a re-sent chunk must not be preceded by stale deferred tags. A tag-only packet (not a full chunk,
     * no sections: the rest of a chunk too big for one packet, see ChunkPacketSplitter) only queues its tags behind the
     * chunk's pending ones; there are no blocks to read and nothing to re-render.
     */
    @Inject(method = "func_147263_a(Lnet/minecraft/network/play/server/SPacketChunkData;)V",
            at = @At(value = "INVOKE", target = THREAD_CHECK, shift = At.Shift.AFTER), cancellable = true)
    private void packetbudget$beforeChunkData(SPacketChunkData packet, CallbackInfo ci) {
        if (!packet.func_149274_i() && packet.func_149276_g() == 0) {
            DeferredTiles.enqueue(this.field_147300_g, packet.func_149273_e(), packet.func_149271_f(), packet.func_189554_f());
            ci.cancel();
            return;
        }
        DeferredTiles.flushChunk(packet.func_149273_e(), packet.func_149271_f());
    }

    /** handleChunkData: capture the tag list, give vanilla an empty one. */
    @Redirect(method = "func_147263_a(Lnet/minecraft/network/play/server/SPacketChunkData;)V",
              at = @At(value = "INVOKE", target = "Lnet/minecraft/network/play/server/SPacketChunkData;func_189554_f()Ljava/util/List;"))
    private List<NBTTagCompound> packetbudget$deferTags(SPacketChunkData packet) {
        DeferredTiles.enqueue(this.field_147300_g, packet.func_149273_e(), packet.func_149271_f(), packet.func_189554_f());
        return Collections.emptyList();
    }

    @Inject(method = "func_147234_a(Lnet/minecraft/network/play/server/SPacketBlockChange;)V",
            at = @At(value = "INVOKE", target = THREAD_CHECK, shift = At.Shift.AFTER))
    private void packetbudget$beforeBlockChange(SPacketBlockChange packet, CallbackInfo ci) {
        BlockPos pos = packet.func_179827_b();
        DeferredTiles.flushChunk(pos.func_177958_n() >> 4, pos.func_177952_p() >> 4);
    }

    @Inject(method = "func_147287_a(Lnet/minecraft/network/play/server/SPacketMultiBlockChange;)V",
            at = @At(value = "INVOKE", target = THREAD_CHECK, shift = At.Shift.AFTER))
    private void packetbudget$beforeMultiBlockChange(SPacketMultiBlockChange packet, CallbackInfo ci) {
        ChunkPos pos = ((MultiBlockChangeAccessor) packet).packetbudget$getChunkPos();
        DeferredTiles.flushChunk(pos.field_77276_a, pos.field_77275_b);
    }

    @Inject(method = "func_147273_a(Lnet/minecraft/network/play/server/SPacketUpdateTileEntity;)V",
            at = @At(value = "INVOKE", target = THREAD_CHECK, shift = At.Shift.AFTER))
    private void packetbudget$beforeUpdateTileEntity(SPacketUpdateTileEntity packet, CallbackInfo ci) {
        BlockPos pos = packet.func_179823_a();
        DeferredTiles.flushChunk(pos.func_177958_n() >> 4, pos.func_177952_p() >> 4);
    }

    @Inject(method = "func_184326_a(Lnet/minecraft/network/play/server/SPacketUnloadChunk;)V",
            at = @At(value = "INVOKE", target = THREAD_CHECK, shift = At.Shift.AFTER))
    private void packetbudget$beforeUnload(SPacketUnloadChunk packet, CallbackInfo ci) {
        DeferredTiles.dropChunk(packet.func_186940_a(), packet.func_186941_b());
    }
}
