package ru.arthaix.keystone.chunkkeep.mixin;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.NetworkManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.arthaix.keystone.chunkkeep.ChunkPacketSplitter;

/** Every connection to the dedicated server gets ChunkPacketSplitter next to its packet encoder. */
@Mixin(value = NetworkManager.class, remap = false)
public abstract class MixinNetworkManagerSplit {
    @Inject(method = "channelActive(Lio/netty/channel/ChannelHandlerContext;)V", at = @At("RETURN"))
    private void chunkkeep$splitBigChunkPackets(ChannelHandlerContext ctx, CallbackInfo ci) {
        ChunkPacketSplitter.install(ctx);
    }
}
