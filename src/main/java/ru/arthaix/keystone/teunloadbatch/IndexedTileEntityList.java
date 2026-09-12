package ru.arthaix.keystone.teunloadbatch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.tileentity.TileEntity;

/**
 * Replacement for World.loadedTileEntityList with O(1) remove(Object), contains() and removeAll() per element.
 *
 * The list is informational (ticking uses tickableTileEntities), so element order does not matter: removal swaps the
 * last element into the freed slot. Elements live in ArrayList's own array, so everything that reads it directly
 * (iterator, forEach, stream, spliterator, toArray, get) is plain ArrayList, and code that needs an ArrayList still
 * gets one. On top of that an identity index maps each tile entity to one of its positions; further occurrences of the
 * same instance (vanilla can add one twice) are only counted. Rare bulk operations (removeIf, retainAll, sort,
 * inserting in the middle) run as in ArrayList and then rebuild the index.
 *
 * The index is split into SHARDS small hash maps by identity hash: a single map for millions of tile entities grows by
 * allocating arrays of tens of megabytes, which G1 treats as humongous objects and which stalled the server for
 * seconds while chunks loaded. Shards stay far below that size.
 *
 * Robustness: some mods touch this list from other threads (a plain ArrayList silently tolerates that). All methods
 * that change the list or read the index are synchronized, every index lookup is verified against the array before it
 * is trusted, and a mismatch rebuilds the index instead of failing. The first foreign-thread write and every repair
 * (at most once a minute) are logged with a stack trace.
 */
public final class IndexedTileEntityList extends ArrayList<TileEntity> {
    private static final long serialVersionUID = 1L;
    private static final int SHARDS = 256;
    private static final int SHARD_MASK = SHARDS - 1;

    /** instance -> index of one occurrence, -1 if absent; shard chosen by identity hash, created on first use */
    private final Reference2IntOpenHashMap<Object>[] index;
    /** instance -> number of occurrences beyond the indexed one (rare, one small map) */
    private final Reference2IntOpenHashMap<Object> extra = new Reference2IntOpenHashMap<Object>();

    private transient Thread owner;
    private static volatile boolean loggedForeignThread;
    private static volatile long lastRepairLog;
    private static volatile long repairs;

    @SuppressWarnings("unchecked")
    public IndexedTileEntityList(Collection<? extends TileEntity> initial) {
        super(Math.max(16, initial.size()));
        this.index = new Reference2IntOpenHashMap[SHARDS];
        this.extra.defaultReturnValue(0);
        for (TileEntity te : initial) {
            add(te);
        }
    }

    public static long repairs() {
        return repairs;
    }

    // ---------------- index: slot on the element, sharded maps as fallback ----------------
    //
    // A tile entity carries its own slot (TeSlot, added by MixinTileEntitySlot): reading and writing it costs a field
    // access, where the maps cost an identity hash and a probe per operation, and chunk unloads remove hundreds of
    // thousands of tile entities at once. The sharded maps remain for elements without a slot (mixin missing) and for
    // an element whose slot already belongs to another list. The generation makes clearing and rebuilding O(1) for the
    // slots: a slot written under an older generation is treated as absent.

    private int gen;
    private boolean shardsUsed;

    private static int shard(Object o) {
        return System.identityHashCode(o) & SHARD_MASK;
    }

    private int shardGet(Object o) {
        Reference2IntOpenHashMap<Object> m = this.index[shard(o)];
        return m == null ? -1 : m.getInt(o);
    }

    private void shardPut(Object o, int i) {
        int s = shard(o);
        Reference2IntOpenHashMap<Object> m = this.index[s];
        if (m == null) {
            m = new Reference2IntOpenHashMap<Object>();
            m.defaultReturnValue(-1);
            this.index[s] = m;
        }
        m.put(o, i);
        this.shardsUsed = true;
    }

    private void shardRemove(Object o) {
        Reference2IntOpenHashMap<Object> m = this.index[shard(o)];
        if (m != null) {
            m.removeInt(o);
        }
    }

    private void shardClear() {
        for (Reference2IntOpenHashMap<Object> m : this.index) {
            if (m != null) {
                m.clear();
            }
        }
        this.shardsUsed = false;
    }

    private int idxGet(Object o) {
        if (o instanceof TeSlot) {
            TeSlot s = (TeSlot) o;
            if (s.teunloadbatch$owner() == this) {
                return s.teunloadbatch$gen() == this.gen ? s.teunloadbatch$slot() : -1;
            }
        }
        return this.shardsUsed ? shardGet(o) : -1;
    }

    private boolean idxContains(Object o) {
        return idxGet(o) >= 0;
    }

    private void idxPut(Object o, int i) {
        if (o instanceof TeSlot) {
            TeSlot s = (TeSlot) o;
            Object owner = s.teunloadbatch$owner();
            // never take a slot from another list: that list would then lose track of the element
            if (owner == null || owner == this) {
                s.teunloadbatch$setSlot(this, this.gen, i);
                if (this.shardsUsed) {
                    shardRemove(o);
                }
                return;
            }
        }
        shardPut(o, i);
    }

    private void idxRemove(Object o) {
        if (o instanceof TeSlot) {
            TeSlot s = (TeSlot) o;
            if (s.teunloadbatch$owner() == this) {
                s.teunloadbatch$setSlot(null, 0, 0);
                return;
            }
        }
        if (this.shardsUsed) {
            shardRemove(o);
        }
    }

    private void idxClear() {
        this.gen++;
        if (this.shardsUsed) {
            shardClear();
        }
    }

    // ---------------- diagnostics and repair ----------------

    private void touch() {
        Thread t = Thread.currentThread();
        if (this.owner == null) {
            this.owner = t;
        } else if (t != this.owner && !loggedForeignThread) {
            loggedForeignThread = true;
            System.err.println("[teunloadbatch] loadedTileEntityList changed from thread '" + t.getName() + "' (world thread '"
                + this.owner.getName() + "'); access is serialized. Stack of that call:");
            new Throwable().printStackTrace();
        }
    }

    private void repair(String where) {
        repairs++;
        long now = System.currentTimeMillis();
        if (now - lastRepairLog > 60_000L) {
            lastRepairLog = now;
            System.err.println("[teunloadbatch] loadedTileEntityList index out of sync in " + where + " (repair #" + repairs
                + ", thread '" + Thread.currentThread().getName() + "'), rebuilding. Stack:");
            new Throwable().printStackTrace();
        }
        rebuildIndex();
    }

    /** The index entry i is trusted only if the array really holds o there. */
    private boolean indexed(Object o, int i) {
        return i >= 0 && i < size() && super.get(i) == o;
    }

    private int lookup(Object o, String where) {
        int i = idxGet(o);
        if (i >= 0 && !indexed(o, i)) {
            repair(where);
            i = idxGet(o);
        }
        return i;
    }

    /** Verifies the whole index; for tests and diagnostics. */
    public synchronized boolean selfCheck() {
        java.util.IdentityHashMap<Object, Integer> counts = new java.util.IdentityHashMap<Object, Integer>();
        for (int i = 0, n = size(); i < n; i++) {
            Object o = super.get(i);
            Integer c = counts.get(o);
            counts.put(o, c == null ? 1 : c + 1);
        }
        for (java.util.Map.Entry<Object, Integer> en : counts.entrySet()) {
            Object o = en.getKey();
            if (!indexed(o, idxGet(o)) || this.extra.getInt(o) != en.getValue() - 1) {
                return false;
            }
        }
        for (Reference2IntOpenHashMap<Object> m : this.index) {
            if (m == null) {
                continue;
            }
            for (Reference2IntMap.Entry<Object> en : m.reference2IntEntrySet()) {
                // the maps only hold members whose slot belongs to someone else
                if (!indexed(en.getKey(), en.getIntValue()) || shard(en.getKey()) != shardOf(m) || !counts.containsKey(en.getKey())) {
                    return false;
                }
            }
        }
        for (Reference2IntMap.Entry<Object> en : this.extra.reference2IntEntrySet()) {
            if (!counts.containsKey(en.getKey())) {
                return false;
            }
        }
        return true;
    }

    private int shardOf(Reference2IntOpenHashMap<Object> m) {
        for (int s = 0; s < SHARDS; s++) {
            if (this.index[s] == m) {
                return s;
            }
        }
        return -1;
    }

    // ---------------- index bookkeeping ----------------

    private void indexAdded(Object e, int i) {
        if (idxContains(e)) {
            this.extra.addTo(e, 1);
        } else {
            idxPut(e, i);
        }
    }

    private boolean decExtra(Object e) {
        if (this.extra.isEmpty()) {
            return false;
        }
        int n = this.extra.getInt(e);
        if (n <= 0) {
            return false;
        }
        if (n == 1) {
            this.extra.removeInt(e);
        } else {
            this.extra.put(e, n - 1);
        }
        return true;
    }

    private int findIdentity(Object e) {
        for (int i = 0, n = size(); i < n; i++) {
            if (super.get(i) == e) {
                return i;
            }
        }
        return -1;
    }

    private void rebuildIndex() {
        idxClear();
        this.extra.clear();
        for (int i = 0, n = size(); i < n; i++) {
            indexAdded(super.get(i), i);
        }
    }

    // ---------------- single-element operations ----------------

    @Override
    public synchronized boolean add(TileEntity e) {
        touch();
        super.add(e);
        indexAdded(e, size() - 1);
        return true;
    }

    @Override
    public synchronized void add(int i, TileEntity e) {
        if (i == size()) {
            add(e);
        } else {
            touch();
            super.add(i, e);
            rebuildIndex();
        }
    }

    @Override
    public synchronized TileEntity set(int i, TileEntity e) {
        touch();
        TileEntity current = super.get(i);
        lookup(current, "set");
        TileEntity old = super.set(i, e);
        if (old == e) {
            return old;
        }
        int oi = idxGet(old);
        if (oi == i) {
            if (decExtra(old)) {
                int j = findIdentity(old);
                if (j >= 0) {
                    idxPut(old, j);
                } else {
                    idxRemove(old);
                }
            } else {
                idxRemove(old);
            }
        } else if (!decExtra(old)) {
            rebuildIndex();
            return old;
        }
        indexAdded(e, i);
        return old;
    }

    @Override
    public synchronized TileEntity remove(int i) {
        touch();
        int last = size() - 1;
        if (i < 0 || i > last) {
            throw new IndexOutOfBoundsException("Index: " + i + ", Size: " + size());
        }
        TileEntity e = super.get(i);
        TileEntity moved = super.get(last);
        lookup(e, "remove(int)");
        lookup(moved, "remove(int)");
        super.remove(last);
        if (i != last) {
            super.set(i, moved);
        }
        int ei = idxGet(e);
        if (ei == i) {
            if (decExtra(e)) {
                int j = findIdentity(e);
                if (j >= 0) {
                    idxPut(e, j);
                } else {
                    idxRemove(e);
                }
            } else {
                idxRemove(e);
            }
        } else {
            if (!decExtra(e)) {
                rebuildIndex();
                return e;
            }
            if (ei == last && i != last) {
                // the indexed occurrence was the last element, now moved into i
                idxPut(e, i);
            }
        }
        if (i != last && moved != e && idxGet(moved) == last) {
            idxPut(moved, i);
        }
        return e;
    }

    @Override
    public synchronized boolean remove(Object o) {
        int i = lookup(o, "remove(Object)");
        if (i < 0) {
            return false;
        }
        remove(i);
        return true;
    }

    @Override
    public synchronized boolean contains(Object o) {
        return lookup(o, "contains") >= 0;
    }

    @Override
    public synchronized int indexOf(Object o) {
        int i = lookup(o, "indexOf");
        return i >= 0 && this.extra.getInt(o) > 0 ? super.indexOf(o) : i;
    }

    @Override
    public synchronized int lastIndexOf(Object o) {
        int i = lookup(o, "lastIndexOf");
        return i >= 0 && this.extra.getInt(o) > 0 ? super.lastIndexOf(o) : i;
    }

    // ---------------- bulk operations ----------------

    @Override
    public synchronized boolean removeAll(Collection<?> c) {
        if (c == this) {
            boolean had = !isEmpty();
            clear();
            return had;
        }
        // Removing most of the list: one pass over the array plus an index rebuild beats per-element updates. It asks
        // c.contains exactly like ArrayList.removeAll, so the result is the same.
        if (c instanceof Set && !c.isEmpty() && (long) c.size() * 2L >= size()) {
            touch();
            boolean removed = super.removeIf(c::contains);
            if (removed) {
                rebuildIndex();
            }
            return removed;
        }
        boolean changed = false;
        for (Object o : c) {
            // o can occur more than once only while some element does; decide before removing, which updates the counts
            boolean mayRepeat = !this.extra.isEmpty();
            if (!remove(o)) {
                continue;
            }
            changed = true;
            while (mayRepeat && remove(o)) {
                // remove every occurrence
            }
        }
        return changed;
    }

    @Override
    public synchronized boolean addAll(Collection<? extends TileEntity> c) {
        if (c == this) {
            c = new ArrayList<TileEntity>(this);
        }
        ensureCapacity(size() + c.size());
        for (TileEntity e : c) {
            add(e);
        }
        return !c.isEmpty();
    }

    @Override
    public synchronized boolean addAll(int i, Collection<? extends TileEntity> c) {
        if (i == size()) {
            return addAll(c);
        }
        touch();
        boolean changed = super.addAll(i, c);
        rebuildIndex();
        return changed;
    }

    @Override
    public synchronized boolean retainAll(Collection<?> c) {
        touch();
        boolean changed = super.retainAll(c);
        if (changed) {
            rebuildIndex();
        }
        return changed;
    }

    @Override
    public synchronized boolean removeIf(Predicate<? super TileEntity> filter) {
        touch();
        boolean changed = super.removeIf(filter);
        if (changed) {
            rebuildIndex();
        }
        return changed;
    }

    @Override
    public synchronized void replaceAll(UnaryOperator<TileEntity> operator) {
        touch();
        super.replaceAll(operator);
        rebuildIndex();
    }

    @Override
    public synchronized void sort(Comparator<? super TileEntity> c) {
        touch();
        super.sort(c);
        rebuildIndex();
    }

    @Override
    protected synchronized void removeRange(int from, int to) {
        touch();
        super.removeRange(from, to);
        rebuildIndex();
    }

    @Override
    public synchronized void clear() {
        touch();
        super.clear();
        idxClear();
        this.extra.clear();
    }

    /** Read-only view: ArrayList's live sub-list writes to the array directly and would bypass the index. */
    @Override
    public List<TileEntity> subList(int from, int to) {
        return Collections.unmodifiableList(super.subList(from, to));
    }

    @Override
    public synchronized Object clone() {
        return new ArrayList<TileEntity>(this);
    }
}
