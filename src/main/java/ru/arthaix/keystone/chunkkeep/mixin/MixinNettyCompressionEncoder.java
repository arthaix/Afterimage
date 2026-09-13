package ru.arthaix.keystone.chunkkeep.mixin;

import java.util.zip.Deflater;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.NettyCompressionEncoder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla compresses every packet at zlib's default level 6 on the connection's single netty thread. A chunk packet of
 * an imported model runs to tens of MB; at level 6 that is seconds per chunk, during which every later packet of that
 * player, keep-alives included, waits. Packets over -Dchunkkeep.fastDeflateKB (256) are compressed at level 1: three to
 * four times faster, about a tenth bigger.
 */
@Mixin(value = NettyCompressionEncoder.class, remap = false)
public abstract class MixinNettyCompressionEncoder {
    private static final int FAST_FROM = Integer.getInteger("chunkkeep.fastDeflateKB", 256) << 10;

    @Shadow
    @Final
    private Deflater field_179300_b;

    @Unique
    private int chunkkeep$level = Deflater.DEFAULT_COMPRESSION;

    @Inject(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Lio/netty/buffer/ByteBuf;)V", at = @At("HEAD"))
    private void chunkkeep$levelBySize(ChannelHandlerContext ctx, ByteBuf in, ByteBuf out, CallbackInfo ci) {
        int level = in.readableBytes() >= FAST_FROM ? Deflater.BEST_SPEED : Deflater.DEFAULT_COMPRESSION;
        if (level != this.chunkkeep$level) {
            this.chunkkeep$level = level;
            this.field_179300_b.setLevel(level);
        }
    }
}
