import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Random;
import java.util.Set;

import ru.arthaix.keystone.teunloadbatch.DeferredRemovalList;

/** DeferredRemovalList against a plain ArrayList that removes at once, under random operation sequences. */
public class DeferredRemovalListTest {
    static final class Tile {
        final int id;

        Tile(int id) {
            this.id = id;
        }

        @Override
        public String toString() {
            return "T" + this.id;
        }
    }

    /** equals by id: must be removed at once, with equals semantics */
    static final class EqTile {
        final int id;

        EqTile(int id) {
            this.id = id;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof EqTile && ((EqTile) o).id == this.id;
        }

        @Override
        public int hashCode() {
            return this.id;
        }
    }

    static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    /** element-wise identity comparison, reading the deferred list through its iterator and get */
    static void same(List<Object> ref, DeferredRemovalList<Object> d, String where) {
        check(ref.size() == d.size(), where + ": size " + ref.size() + " vs " + d.size());
        int i = 0;
        for (Object o : d) {
            check(ref.get(i) == o, where + ": element " + i);
            i++;
        }
        for (int j = 0; j < ref.size(); j++) {
            check(ref.get(j) == d.get(j), where + ": get " + j);
        }
    }

    public static void main(String[] args) {
        long seedBase = args.length > 0 ? Long.parseLong(args[0]) : 1L;
        int runs = 400;
        long ops = 0;
        for (int run = 0; run < runs; run++) {
            Random r = new Random(seedBase * 1000003L + run);
            int poolSize = 1 + r.nextInt(r.nextBoolean() ? 8 : 300);
            Object[] pool = new Object[poolSize];
            for (int i = 0; i < poolSize; i++) {
                pool[i] = r.nextInt(10) == 0 ? new EqTile(r.nextInt(Math.max(1, poolSize / 3))) : new Tile(i);
            }
            List<Object> ref = new ArrayList<Object>();
            DeferredRemovalList<Object> d = new DeferredRemovalList<Object>(Collections.emptyList());
            int steps = 50 + r.nextInt(3000);
            for (int s = 0; s < steps; s++) {
                ops++;
                Object e = pool[r.nextInt(poolSize)];
                int op = r.nextInt(100);
                String where = "run " + run + " step " + s + " op " + op;
                if (op < 30) {
                    ref.add(e);
                    d.add(e);
                } else if (op < 62) {
                    ref.remove(e);
                    d.removeLater(e);
                } else if (op < 64) {
                    check(ref.remove(e) == d.remove(e), where + ": remove(Object) result");
                } else if (op < 66 && !ref.isEmpty()) {
                    int i = r.nextInt(ref.size());
                    check(ref.remove(i) == d.remove(i), where + ": remove(int)");
                } else if (op < 68) {
                    int i = r.nextInt(ref.size() + 1);
                    ref.add(i, e);
                    d.add(i, e);
                } else if (op < 70 && !ref.isEmpty()) {
                    int i = r.nextInt(ref.size());
                    check(ref.set(i, e) == d.set(i, e), where + ": set");
                } else if (op < 73) {
                    Set<Object> rm = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
                    int k = r.nextInt(6);
                    for (int i = 0; i < k; i++) {
                        rm.add(pool[r.nextInt(poolSize)]);
                    }
                    check(ref.removeAll(rm) == d.removeAll(rm), where + ": removeAll");
                } else if (op < 75) {
                    Iterator<Object> a = ref.iterator();
                    Iterator<Object> b = d.iterator();
                    while (a.hasNext()) {
                        check(b.hasNext(), where + ": iterator length");
                        Object x = a.next();
                        check(x == b.next(), where + ": iterator element");
                        if (r.nextInt(4) == 0) {
                            a.remove();
                            b.remove();
                        }
                    }
                    check(!b.hasNext(), where + ": iterator end");
                } else if (op < 77 && !ref.isEmpty()) {
                    int from = r.nextInt(ref.size());
                    int to = from + r.nextInt(ref.size() - from + 1);
                    ref.subList(from, to).clear();
                    d.subList(from, to).clear();
                } else if (op < 79) {
                    final int mod = 2 + r.nextInt(5);
                    check(ref.removeIf(x -> System.identityHashCode(x) % mod == 0) == d.removeIf(x -> System.identityHashCode(x) % mod == 0),
                        where + ": removeIf");
                } else if (op < 82) {
                    check(ref.contains(e) == d.contains(e), where + ": contains");
                    check(ref.indexOf(e) == d.indexOf(e), where + ": indexOf");
                    check(ref.lastIndexOf(e) == d.lastIndexOf(e), where + ": lastIndexOf");
                } else if (op < 84) {
                    check(ref.isEmpty() == d.isEmpty(), where + ": isEmpty");
                    Object[] x = ref.toArray();
                    Object[] y = d.toArray();
                    check(x.length == y.length, where + ": toArray length");
                    for (int i = 0; i < x.length; i++) {
                        check(x[i] == y[i], where + ": toArray " + i);
                    }
                } else if (op < 85) {
                    ref.clear();
                    d.clear();
                } else if (op < 87 && !ref.isEmpty()) {
                    ListIterator<Object> a = ref.listIterator(r.nextInt(ref.size()));
                    ListIterator<Object> b = d.listIterator(a.nextIndex());
                    if (a.hasNext()) {
                        check(a.next() == b.next(), where + ": listIterator next");
                        a.set(e);
                        b.set(e);
                        a.add(e);
                        b.add(e);
                    }
                } else if (op < 89) {
                    List<Object> copy = new ArrayList<Object>(d);
                    check(copy.size() == ref.size(), where + ": copy constructor");
                    for (int i = 0; i < copy.size(); i++) {
                        check(copy.get(i) == ref.get(i), where + ": copy " + i);
                    }
                } else if (op < 90) {
                    List<Object> all = new ArrayList<Object>();
                    d.forEach(all::add);
                    check(all.size() == ref.size(), where + ": forEach");
                    check(d.stream().count() == ref.size(), where + ": stream");
                } else if (op < 93) {
                    // a removal and a re-add of the same element before anything reads the list (block replaced twice)
                    ref.remove(e);
                    d.removeLater(e);
                    ref.add(e);
                    d.add(e);
                } else if (op < 95) {
                    same(ref, d, where);
                }
            }
            same(ref, d, "run " + run + " end");
            check(d.pendingRemovals() == 0, "run " + run + ": pending after read");
        }
        System.out.println("random: " + runs + " runs, " + ops + " operations, all equal");

        // scale: a WorldEdit-like replacement of tile entities spread over a large tick list
        int n = 400_000;
        int replaced = 30_000;
        Random r = new Random(7);
        List<Object> base = new ArrayList<Object>(n);
        for (int i = 0; i < n; i++) {
            base.add(new Tile(i));
        }
        int[] victims = new int[replaced];
        for (int i = 0; i < replaced; i++) {
            victims[i] = r.nextInt(n);
        }
        List<Object> plain = new ArrayList<Object>(base);
        long t0 = System.nanoTime();
        for (int i = 0; i < replaced; i++) {
            plain.remove(base.get(victims[i]));
            plain.add(new Tile(-1 - i));
        }
        int plainSize = plain.size();
        long plainMs = (System.nanoTime() - t0) / 1_000_000L;
        DeferredRemovalList<Object> def = new DeferredRemovalList<Object>(base);
        t0 = System.nanoTime();
        for (int i = 0; i < replaced; i++) {
            def.removeLater(base.get(victims[i]));
            def.add(new Tile(-1 - i));
        }
        int defSize = def.size();
        long defMs = (System.nanoTime() - t0) / 1_000_000L;
        check(plainSize == defSize, "scale sizes " + plainSize + " vs " + defSize);
        for (int i = 0; i < plainSize; i++) {
            Object a = plain.get(i);
            Object b = def.get(i);
            check(a == b || (((Tile) a).id < 0 && ((Tile) b).id < 0 && ((Tile) a).id == ((Tile) b).id), "scale element " + i);
        }
        System.out.println("scale: " + replaced + " replacements in a list of " + n + ": ArrayList " + plainMs + " ms, deferred " + defMs + " ms");
    }
}
