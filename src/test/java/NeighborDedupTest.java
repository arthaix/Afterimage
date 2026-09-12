import java.util.ArrayList;
import java.util.Map;
import java.util.Random;

import com.creativemd.creativecore.common.utils.type.HashMapList;

import net.minecraft.util.math.BlockPos;
import ru.arthaix.keystone.ltfix.NeighborDedup;

/**
 * The deduplicated NeighborUpdateOrganizer.add (same steps as MixinNeighborUpdateOrganizer) against the original
 * "if (!positions.contains(world, pos)) positions.add(world, pos)", on the real CreativeCore HashMapList, with the end
 * of tick clear, world unload and other code adding to the queue directly.
 */
public class NeighborDedupTest {
    static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    static void same(HashMapList<Object, BlockPos> a, HashMapList<Object, BlockPos> b, String where) {
        check(a.size() == b.size(), where + ": keys " + a.size() + " vs " + b.size());
        java.util.Iterator<Map.Entry<Object, ArrayList<BlockPos>>> ia = a.entrySet().iterator();
        java.util.Iterator<Map.Entry<Object, ArrayList<BlockPos>>> ib = b.entrySet().iterator();
        while (ia.hasNext()) {
            Map.Entry<Object, ArrayList<BlockPos>> ea = ia.next();
            Map.Entry<Object, ArrayList<BlockPos>> eb = ib.next();
            check(ea.getKey() == eb.getKey(), where + ": key order");
            check(ea.getValue().equals(eb.getValue()), where + ": positions of " + ea.getKey() + "\n  " + ea.getValue() + "\n  " + eb.getValue());
        }
    }

    public static void main(String[] args) {
        long ops = 0;
        for (int run = 0; run < 300; run++) {
            Random r = new Random(run * 7919L + 1);
            Object[] worlds = new Object[1 + r.nextInt(3)];
            for (int i = 0; i < worlds.length; i++) {
                worlds[i] = new Object();
            }
            int range = 1 + r.nextInt(r.nextBoolean() ? 3 : 20);
            HashMapList<Object, BlockPos> original = new HashMapList<Object, BlockPos>();
            HashMapList<Object, BlockPos> fast = new HashMapList<Object, BlockPos>();
            NeighborDedup dedup = new NeighborDedup();
            int steps = 20 + r.nextInt(4000);
            for (int s = 0; s < steps; s++) {
                ops++;
                Object w = worlds[r.nextInt(worlds.length)];
                BlockPos p = new BlockPos(r.nextInt(range), r.nextInt(range), r.nextInt(range));
                if (r.nextBoolean()) {
                    p = new BlockPos.MutableBlockPos(p);
                }
                int op = r.nextInt(100);
                if (op < 85) {
                    if (!original.contains(w, p)) {
                        original.add(w, p);
                    }
                    ArrayList<BlockPos> current = fast.getValues(w);
                    if (dedup.isNew(w, current, p)) {
                        fast.add(w, p);
                        dedup.added(w, fast.getValues(w), p);
                    }
                } else if (op < 90) {
                    original.clear();
                    fast.clear();
                } else if (op < 93) {
                    original.removeKey(w);
                    fast.removeKey(w);
                } else if (op < 97) {
                    // other code adding to the queue directly, duplicates included
                    BlockPos q = p.func_185334_h();
                    original.add(w, q);
                    fast.add(w, q);
                }
                if (s % 97 == 0) {
                    same(original, fast, "run " + run + " step " + s);
                }
            }
            same(original, fast, "run " + run + " end");
        }
        System.out.println("random: 300 runs, " + ops + " operations, queues equal");

        // one tick of a large edit: 60k notifications over 25k distinct tile positions
        Object world = new Object();
        Random r = new Random(3);
        BlockPos[] notes = new BlockPos[60_000];
        for (int i = 0; i < notes.length; i++) {
            notes[i] = new BlockPos(r.nextInt(50), r.nextInt(10), r.nextInt(50));
        }
        HashMapList<Object, BlockPos> original = new HashMapList<Object, BlockPos>();
        long t0 = System.nanoTime();
        for (BlockPos p : notes) {
            if (!original.contains(world, p)) {
                original.add(world, p);
            }
        }
        long originalMs = (System.nanoTime() - t0) / 1_000_000L;
        HashMapList<Object, BlockPos> fast = new HashMapList<Object, BlockPos>();
        NeighborDedup dedup = new NeighborDedup();
        t0 = System.nanoTime();
        for (BlockPos p : notes) {
            if (dedup.isNew(world, fast.getValues(world), p)) {
                fast.add(world, p);
                dedup.added(world, fast.getValues(world), p);
            }
        }
        long fastMs = (System.nanoTime() - t0) / 1_000_000L;
        same(original, fast, "scale");
        System.out.println("scale: " + notes.length + " notifications, " + original.getValues(world).size() + " queued: original " + originalMs
            + " ms, dedup " + fastMs + " ms");
    }
}
