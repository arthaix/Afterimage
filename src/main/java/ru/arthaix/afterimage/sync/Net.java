package ru.arthaix.afterimage.sync;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.handshake.NetworkDispatcher;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;
import ru.arthaix.afterimage.ClientSync;

/**
 * The "afterimage" channel.
 *
 * Server to client: Hello (protocol, world id, dimension) when a player with Afterimage joins or changes dimension.
 * Client to server: Sync (dimension, last server tick the client has fully applied).
 * Server to client: Changes (chunk key, server tick pairs) as the answer to Sync, and every second for new changes.
 *
 * The client handlers only queue the message; it is applied on the client thread by ClientSync, which is never loaded
 * on a dedicated server.
 */
public final class Net {
    public static final int PROTOCOL = 1;
    private static final int MAX_PAIRS = 16000;
    public static SimpleNetworkWrapper channel;

    private Net() {
    }

    public static void init() {
        channel = NetworkRegistry.INSTANCE.newSimpleChannel("afterimage");
        channel.registerMessage(HelloHandler.class, Hello.class, 0, Side.CLIENT);
        channel.registerMessage(SyncHandler.class, Sync.class, 1, Side.SERVER);
        channel.registerMessage(ChangesHandler.class, Changes.class, 2, Side.CLIENT);
    }

    public static boolean hasAfterimage(EntityPlayerMP p) {
        try {
            NetworkDispatcher d = NetworkDispatcher.get(p.field_71135_a.field_147371_a);
            return d != null && d.getModList() != null && d.getModList().containsKey("afterimage");
        } catch (Throwable t) {
            return false;
        }
    }

    public static void sendHello(EntityPlayerMP p) {
        String id = ChangeTracker.worldId();
        if (id == null || !hasAfterimage(p)) {
            return;
        }
        channel.sendTo(new Hello(PROTOCOL, id, p.field_71093_bK), p);
    }

    public static void sendChanges(EntityPlayerMP p, int dim, long[] pairs, boolean initial, long serverTime) {
        int n = pairs.length / 2;
        if (n == 0) {
            if (initial) {
                channel.sendTo(new Changes(dim, true, true, serverTime, pairs, 0, 0), p);
            }
            return;
        }
        for (int off = 0; off < n; off += MAX_PAIRS) {
            int cnt = Math.min(MAX_PAIRS, n - off);
            channel.sendTo(new Changes(dim, initial, off + cnt >= n, serverTime, pairs, off, cnt), p);
        }
    }

    // ---------------- messages ----------------

    public static final class Hello implements IMessage {
        public int protocol;
        public String worldId;
        public int dim;

        public Hello() {
        }

        Hello(int protocol, String worldId, int dim) {
            this.protocol = protocol;
            this.worldId = worldId;
            this.dim = dim;
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(this.protocol);
            ByteBufUtils.writeUTF8String(buf, this.worldId);
            buf.writeInt(this.dim);
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            this.protocol = buf.readInt();
            this.worldId = ByteBufUtils.readUTF8String(buf);
            this.dim = buf.readInt();
        }
    }

    public static final class Sync implements IMessage {
        public int protocol;
        public int dim;
        public long since;

        public Sync() {
        }

        public Sync(int protocol, int dim, long since) {
            this.protocol = protocol;
            this.dim = dim;
            this.since = since;
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(this.protocol);
            buf.writeInt(this.dim);
            buf.writeLong(this.since);
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            this.protocol = buf.readInt();
            this.dim = buf.readInt();
            this.since = buf.readLong();
        }
    }

    public static final class Changes implements IMessage {
        public int dim;
        /** part of the answer to Sync (as opposed to a live update) */
        public boolean initial;
        /** last part of this batch */
        public boolean last;
        /** server tick when the batch was taken: every change up to it is included */
        public long serverTime;
        /** chunk key, tick, chunk key, tick, ... */
        public long[] pairs;
        private int off;
        private int cnt;

        public Changes() {
        }

        Changes(int dim, boolean initial, boolean last, long serverTime, long[] pairs, int off, int cnt) {
            this.dim = dim;
            this.initial = initial;
            this.last = last;
            this.serverTime = serverTime;
            this.pairs = pairs;
            this.off = off;
            this.cnt = cnt;
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(this.dim);
            buf.writeBoolean(this.initial);
            buf.writeBoolean(this.last);
            buf.writeLong(this.serverTime);
            buf.writeInt(this.cnt);
            for (int i = 0; i < this.cnt; i++) {
                buf.writeLong(this.pairs[2 * (this.off + i)]);
                buf.writeLong(this.pairs[2 * (this.off + i) + 1]);
            }
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            this.dim = buf.readInt();
            this.initial = buf.readBoolean();
            this.last = buf.readBoolean();
            this.serverTime = buf.readLong();
            this.cnt = buf.readInt();
            this.off = 0;
            this.pairs = new long[this.cnt * 2];
            for (int i = 0; i < this.pairs.length; i++) {
                this.pairs[i] = buf.readLong();
            }
        }
    }

    // ---------------- handlers ----------------

    public static final class HelloHandler implements IMessageHandler<Hello, IMessage> {
        @Override
        public IMessage onMessage(Hello message, MessageContext ctx) {
            ClientSync.receive(message);
            return null;
        }
    }

    public static final class ChangesHandler implements IMessageHandler<Changes, IMessage> {
        @Override
        public IMessage onMessage(Changes message, MessageContext ctx) {
            ClientSync.receive(message);
            return null;
        }
    }

    public static final class SyncHandler implements IMessageHandler<Sync, IMessage> {
        @Override
        public IMessage onMessage(final Sync message, MessageContext ctx) {
            final EntityPlayerMP p = ctx.getServerHandler().field_147369_b;
            p.func_71121_q().func_152344_a(() -> {
                if (message.protocol != PROTOCOL) {
                    return;
                }
                long now = p.field_70170_p.func_82737_E();
                sendChanges(p, message.dim, ChangeTracker.since(message.dim, message.since), true, now);
            });
            return null;
        }
    }
}
