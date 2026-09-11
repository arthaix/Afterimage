package ru.arthaix.afterimage;

import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraftforge.fml.common.network.handshake.NetworkDispatcher;
import ru.arthaix.afterimage.sync.Net;

/**
 * Client side of the server sync. Messages arrive on the network thread and are queued; Capture.tick applies them on
 * the client thread right after it has handled a world change, so a Hello always meets the world it belongs to.
 *
 * Hello: switch the disk cache to that world id and dimension, ask the server for every change since the last sync
 * point of that cache. Changes: drop far copies and cached files whose geometry is older than the change.
 */
public final class ClientSync {
    private static final ConcurrentLinkedQueue<Object> INBOX = new ConcurrentLinkedQueue<Object>();
    private static Net.Hello pendingHello;
    private static boolean active;
    private static boolean initialDone;
    private static String worldId;
    private static int dim;
    private static long chunksReceived;
    private static long lastServerTime;

    private ClientSync() {
    }

    /** Network thread. */
    public static void receive(Object message) {
        INBOX.add(message);
    }

    public static boolean serverHasAfterimage() {
        try {
            NetHandlerPlayClient c = Minecraft.func_71410_x().func_147114_u();
            if (c == null) {
                return false;
            }
            NetworkDispatcher d = NetworkDispatcher.get(c.func_147298_b());
            return d != null && d.getModList() != null && d.getModList().containsKey("afterimage");
        } catch (Throwable t) {
            return false;
        }
    }

    /** Client thread, on a world change: the previous handshake no longer applies. */
    public static void onWorld() {
        active = false;
        initialDone = false;
        worldId = null;
    }

    /** Client thread, every client tick after world-change handling. */
    public static void drain() {
        try {
            if (pendingHello != null && Minecraft.func_71410_x().field_71441_e != null) {
                Net.Hello h = pendingHello;
                pendingHello = null;
                hello(h);
            }
            Object m;
            while ((m = INBOX.poll()) != null) {
                if (m instanceof Net.Hello) {
                    hello((Net.Hello) m);
                } else if (m instanceof Net.Changes) {
                    changes((Net.Changes) m);
                }
            }
        } catch (Throwable t) {
            Capture.logError("sync", t);
        }
    }

    private static void hello(Net.Hello m) {
        if (Minecraft.func_71410_x().field_71441_e == null) {
            pendingHello = m;
            return;
        }
        if (m.protocol != Net.PROTOCOL) {
            Capture.logInfo("sync: server speaks protocol " + m.protocol + ", this client " + Net.PROTOCOL + "; not syncing");
            return;
        }
        worldId = m.worldId;
        dim = m.dim;
        active = true;
        initialDone = false;
        long since = Disk.beginServerWorld(m.worldId, m.dim);
        Net.channel.sendToServer(new Net.Sync(Net.PROTOCOL, m.dim, since));
    }

    private static void changes(Net.Changes m) {
        if (!active || m.dim != dim) {
            return;
        }
        long[] p = m.pairs;
        for (int i = 0; i + 1 < p.length; i += 2) {
            long key = p[i];
            long t = p[i + 1];
            // ChunkPos.asLong: x in the low 32 bits, z in the high 32 bits
            int cx = (int) key;
            int cz = (int) (key >>> 32);
            Far.invalidateChunk(cx, cz, t);
            Disk.invalidateChunk(cx, cz, t);
            chunksReceived++;
        }
        if (m.initial && m.last && !initialDone) {
            initialDone = true;
            Disk.serverSyncDone();
        }
        if (initialDone) {
            lastServerTime = Math.max(lastServerTime, m.serverTime);
            Disk.noteSync(m.serverTime);
        }
    }

    public static String summary() {
        if (!active) {
            return "sync: " + (serverHasAfterimage() ? "waiting for the server" : "the server has no Afterimage, local cache only");
        }
        return "sync: world " + worldId + " dimension " + dim + ", " + (initialDone ? "up to date" : "receiving changes")
            + ", changed chunks received " + chunksReceived + ", last server tick " + lastServerTime;
    }
}
