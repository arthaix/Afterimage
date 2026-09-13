package ru.arthaix.keystone.chunkkeep;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.SPacketChunkData;
import ru.arthaix.keystone.chunkkeep.mixin.ChunkPacketEstimate;
import ru.arthaix.keystone.chunkkeep.mixin.NetworkManagerAccessor;
import ru.arthaix.keystone.chunkkeep.mixin.SPacketChunkDataAccessor;

/**
 * Chunk packets queued to a connection but not yet encoded. The server thread hands a packet to netty at once, but one
 * netty thread per connection serializes, compresses and writes them in order; a keep-alive queued behind tens of
 * chunk packets of an imported model (up to 33 MB each) reached the player only after the whole backlog, the reply came
 * back late and the server disconnected them with "Timed out". Chunk sending in PlayerChunkMap.tick() now waits while a
 * player's backlog is over -Dchunkkeep.backlogMB (12): the entry stays pending, as for a chunk that is not ready.
 * Sizes are estimates: block data plus tile entity tags at the average tag size the splitter has measured.
 */
public final class SendBacklog {
    private static final long LIMIT = Long.getLong("chunkkeep.backlogMB", 12L) << 20;
    private static final AttributeKey<AtomicLong> PENDING = AttributeKey.valueOf("keystone:chunk_backlog");
    private static volatile long averageTag = 1024L;
    private static final AtomicLong WAITS = new AtomicLong();

    private SendBacklog() {
    }

    /** ChunkPacketSplitter: measured bytes of a packet's tags, to refine the estimate. */
    public static void learn(long tagBytes, int tags) {
        if (tags > 0) {
            long avg = tagBytes / tags;
            averageTag = (averageTag * 7 + avg) / 8;
        }
    }

    private static AtomicLong counter(Channel channel) {
        AtomicLong c = channel.attr(PENDING).get();
        if (c == null) {
            c = new AtomicLong();
            AtomicLong prev = channel.attr(PENDING).setIfAbsent(c);
            if (prev != null) {
                c = prev;
            }
        }
        return c;
    }

    /** NetworkManager.sendPacket (server thread): a chunk packet is queued to this channel. */
    public static void queued(Channel channel, SPacketChunkData packet) {
        if (channel == null) {
            return;
        }
        SPacketChunkDataAccessor a = (SPacketChunkDataAccessor) packet;
        byte[] data = a.chunkkeep$getData();
        List<?> tags = a.chunkkeep$getTags();
        long estimate = 64L + (data == null ? 0 : data.length) + (tags == null ? 0 : tags.size() * averageTag);
        ((ChunkPacketEstimate) packet).chunkkeep$setEstimate(estimate);
        counter(channel).addAndGet(estimate);
    }

    /** ChunkPacketSplitter.write (netty thread): the packet is being encoded now. */
    public static void encoding(Channel channel, SPacketChunkData packet) {
        long estimate = ((ChunkPacketEstimate) packet).chunkkeep$estimate();
        if (estimate > 0 && channel != null) {
            ((ChunkPacketEstimate) packet).chunkkeep$setEstimate(0L);
            AtomicLong c = channel.attr(PENDING).get();
            if (c != null) {
                c.addAndGet(-estimate);
            }
        }
    }

    /** Server thread: whether any of these players has more than the limit waiting to be encoded. */
    public static boolean full(List<EntityPlayerMP> players) {
        if (LIMIT <= 0 || players == null) {
            return false;
        }
        for (EntityPlayerMP p : players) {
            if (p == null || p.field_71135_a == null) {
                continue;
            }
            Channel ch = ((NetworkManagerAccessor) p.field_71135_a.field_147371_a).chunkkeep$channel();
            if (ch == null) {
                continue;
            }
            AtomicLong c = ch.attr(PENDING).get();
            if (c != null && c.get() > LIMIT) {
                WAITS.incrementAndGet();
                return true;
            }
        }
        return false;
    }

    public static String stats() {
        return "sendBacklog waits " + WAITS.get() + " avgTag " + averageTag;
    }
}
