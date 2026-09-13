package ru.arthaix.keystone.chunkkeep;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.server.SPacketChunkData;
import ru.arthaix.keystone.chunkkeep.mixin.SPacketChunkDataAccessor;

/**
 * A chunk packet carries the update tag of every tile entity in the chunk. A chunk of an imported model with up to 200
 * LittleTiles tiles per block came to 2.7 MB even compressed, over the protocol's 2 MB per packet: the server's frame
 * encoder threw "unable to fit 2683403 into 3" and disconnected the player each time the chunk was sent to them, that is
 * each time they came close. (The client would also refuse anything over 2 MB uncompressed.)
 *
 * Such a packet now goes out as the chunk with as many tags as fit in -Dchunkkeep.packetBytes (1900000, uncompressed),
 * followed by tag-only packets for the same chunk: not a full chunk and no sections, so a client only applies their
 * tags. All parts are written inside the same netty write, so no other packet can get between them. One tag bigger
 * than the budget on its own can never be sent and is left out (logged). Packets that fit are passed on untouched; the
 * sizes are counted by walking the tags (TagCache bytes are counted by their length), not by writing them.
 */
@ChannelHandler.Sharable
public final class ChunkPacketSplitter extends ChannelOutboundHandlerAdapter {
    private static final String NAME = "keystone:chunk_split";
    private static final long BUDGET = Long.getLong("chunkkeep.packetBytes", 1_900_000L);
    /** packet id, x, z, full flag, section mask, data length, tag count */
    private static final long HEAD = 32L;
    private static final ChunkPacketSplitter INSTANCE = new ChunkPacketSplitter();

    private static final AtomicLong SPLIT = new AtomicLong();
    private static final AtomicLong EXTRA = new AtomicLong();
    private static final AtomicLong DROPPED = new AtomicLong();
    private static volatile boolean failureLogged;

    private ChunkPacketSplitter() {
    }

    /** NetworkManager.channelActive of a server connection (netty thread). */
    public static void install(ChannelHandlerContext ctx) {
        try {
            ChannelPipeline pipeline = ctx.pipeline();
            if (pipeline.get("encoder") != null && pipeline.get(NAME) == null) {
                pipeline.addAfter("encoder", NAME, INSTANCE);
            }
        } catch (Throwable t) {
            System.out.println("[chunkkeep] chunk packet splitter not installed on " + ctx.channel() + ": " + t);
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        List<SPacketChunkData> parts = null;
        if (msg instanceof SPacketChunkData) {
            SendBacklog.encoding(ctx.channel(), (SPacketChunkData) msg);
            try {
                parts = split((SPacketChunkData) msg);
            } catch (Throwable t) {
                if (!failureLogged) {
                    failureLogged = true;
                    System.out.println("[chunkkeep] could not measure a chunk packet, sent as it is: " + t);
                }
            }
        }
        if (parts == null) {
            ctx.write(msg, promise);
            return;
        }
        int last = parts.size() - 1;
        for (int i = 0; i < last; i++) {
            ctx.write(parts.get(i)).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        }
        // the caller's listeners (NetworkManager's failure listener, a disconnect after sending) go on the last part
        ctx.write(parts.get(last), promise);
    }

    /** The parts to send instead of packet, or null when it fits as it is. Writes nothing. */
    static List<SPacketChunkData> split(SPacketChunkData packet) {
        SPacketChunkDataAccessor src = (SPacketChunkDataAccessor) packet;
        List<NBTTagCompound> tags = src.chunkkeep$getTags();
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        byte[] data = src.chunkkeep$getData();
        long head = HEAD + (data == null ? 0 : data.length);
        int n = tags.size();
        long[] sizes = new long[n];
        long total = head;
        for (int i = 0; i < n; i++) {
            sizes[i] = NbtSize.root(tags.get(i));
            total += sizes[i];
        }
        SendBacklog.learn(total - head, n);
        if (total <= BUDGET) {
            return null;
        }

        List<SPacketChunkData> parts = new ArrayList<SPacketChunkData>();
        List<NBTTagCompound> current = new ArrayList<NBTTagCompound>();
        long currentBytes = head;
        boolean first = true;
        int dropped = 0;
        for (int i = 0; i < n; i++) {
            long size = sizes[i];
            if (HEAD + size > BUDGET) {
                dropped++;
                continue;
            }
            if (currentBytes + size > BUDGET && (first || !current.isEmpty())) {
                parts.add(first ? chunk(src, current) : tagsOnly(src, current));
                first = false;
                current = new ArrayList<NBTTagCompound>();
                currentBytes = HEAD;
            }
            current.add(tags.get(i));
            currentBytes += size;
        }
        if (first || !current.isEmpty()) {
            parts.add(first ? chunk(src, current) : tagsOnly(src, current));
        }

        long count = SPLIT.incrementAndGet();
        EXTRA.addAndGet(parts.size() - 1);
        DROPPED.addAndGet(dropped);
        if (count <= 50 || count % 100 == 0 || dropped > 0) {
            System.out.println("[chunkkeep] chunk " + src.chunkkeep$getX() + "," + src.chunkkeep$getZ() + ": " + n + " tile entity tags, "
                + (total >> 10) + " KB, sent as " + parts.size() + " packets"
                + (dropped > 0 ? ", " + dropped + " tags over " + (BUDGET >> 10) + " KB each left out" : "") + " (" + stats() + ")");
        }
        return parts;
    }

    private static SPacketChunkData chunk(SPacketChunkDataAccessor src, List<NBTTagCompound> tags) {
        SPacketChunkData p = new SPacketChunkData();
        SPacketChunkDataAccessor a = (SPacketChunkDataAccessor) p;
        a.chunkkeep$setX(src.chunkkeep$getX());
        a.chunkkeep$setZ(src.chunkkeep$getZ());
        a.chunkkeep$setSections(src.chunkkeep$getSections());
        a.chunkkeep$setData(src.chunkkeep$getData());
        a.chunkkeep$setFull(src.chunkkeep$getFull());
        a.chunkkeep$setTags(tags);
        return p;
    }

    private static SPacketChunkData tagsOnly(SPacketChunkDataAccessor src, List<NBTTagCompound> tags) {
        SPacketChunkData p = new SPacketChunkData();
        SPacketChunkDataAccessor a = (SPacketChunkDataAccessor) p;
        a.chunkkeep$setX(src.chunkkeep$getX());
        a.chunkkeep$setZ(src.chunkkeep$getZ());
        a.chunkkeep$setSections(0);
        a.chunkkeep$setData(new byte[0]);
        a.chunkkeep$setFull(false);
        a.chunkkeep$setTags(tags);
        return p;
    }

    public static String stats() {
        return "split chunk packets " + SPLIT.get() + " extra " + EXTRA.get() + " tags left out " + DROPPED.get() + " " + SendBacklog.stats();
    }
}
