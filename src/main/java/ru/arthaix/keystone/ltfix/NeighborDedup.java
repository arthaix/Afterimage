package ru.arthaix.keystone.ltfix;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.util.math.BlockPos;

/**
 * LittleTiles NeighborUpdateOrganizer.add asked "is this position already queued?" with ArrayList.contains, a scan over
 * every position queued this tick for that world. A WorldEdit edit next to LittleTiles blocks queues the position of
 * every tile block whose neighbour changed, so the checks grew quadratically: 52% of the server time of large edits in
 * the 2026-09-12 play session. A hash set per world answers the same question in constant time
 * (MixinNeighborUpdateOrganizer).
 *
 * The organizer's own list stays the only record of what is queued: the same positions in the same order. The set is
 * rebuilt from that list whenever the list is not the one it saw last or has another size (the organizer clears it at
 * the end of every tick and drops it when the world unloads; other code may add to it). Positions are compared by value,
 * like ArrayList.contains, and stored as immutable copies. -Dltfix.neighborDedup=false turns it off.
 */
public final class NeighborDedup {
    public static final boolean ENABLED = !"false".equals(System.getProperty("ltfix.neighborDedup"));
    private static final int SHRINK = 1 << 14;
    private static long skipped;
    private static long resyncs;

    private final Map<Object, Seen> byKey = new WeakHashMap<Object, Seen>();

    private static final class Seen {
        List<BlockPos> list;
        int size;
        HashSet<BlockPos> set = new HashSet<BlockPos>();

        void sync(List<BlockPos> current) {
            if (this.set.size() > SHRINK) {
                this.set = new HashSet<BlockPos>();
            } else {
                this.set.clear();
            }
            if (current != null) {
                for (BlockPos p : current) {
                    this.set.add(p.func_185334_h());
                }
            }
            this.list = current;
            this.size = current == null ? 0 : current.size();
        }
    }

    private Seen seen(Object key) {
        Seen s = this.byKey.get(key);
        if (s == null) {
            s = new Seen();
            this.byKey.put(key, s);
        }
        return s;
    }

    /** Whether pos is not queued for key yet; current is the key's queue now (null if it has none). */
    public boolean isNew(Object key, List<BlockPos> current, BlockPos pos) {
        Seen s = seen(key);
        if (s.list != current || s.size != (current == null ? 0 : current.size())) {
            s.sync(current);
            resyncs++;
        }
        if (current != null && s.set.contains(pos)) {
            skipped++;
            return false;
        }
        return true;
    }

    /** The caller appended pos; current is the key's queue now. */
    public void added(Object key, List<BlockPos> current, BlockPos pos) {
        Seen s = seen(key);
        if (current != null && current.size() == s.size + 1 && (s.list == current || (s.list == null && s.size == 0))) {
            s.set.add(pos.func_185334_h());
            s.list = current;
            s.size++;
        } else {
            s.sync(current);
            resyncs++;
        }
    }

    public static String stats() {
        return ENABLED ? "neighbour queue duplicates " + skipped + " resyncs " + resyncs : "neighbour dedup off";
    }
}
