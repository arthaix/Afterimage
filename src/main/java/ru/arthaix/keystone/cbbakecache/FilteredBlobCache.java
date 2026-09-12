package ru.arthaix.keystone.cbbakecache;

import java.lang.ref.SoftReference;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import mod.chiselsandbits.chiseledblock.data.VoxelBlob;
import mod.chiselsandbits.chiseledblock.data.VoxelBlobStateInstance;
import mod.chiselsandbits.chiseledblock.data.VoxelBlobStateReference;
import mod.chiselsandbits.helpers.IStateRef;
import mod.chiselsandbits.render.chiseledblock.ChiselLayer;
import ru.arthaix.keystone.cbbakecache.mixin.BlockStateRefAccessor;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Memo of ChiselLayer.filter results.
 *
 * Primary index is keyed by blob CONTENT (VoxelBlobStateInstance, which C&B interns
 * and compares by content), so copy-pasted buildings and chunk re-compiles share the
 * answer. Per layer we remember one of three outcomes:
 *   EMPTY     - nothing of this layer in the blob: caller gets null, no copy, no filter
 *   UNCHANGED - the whole blob renders in this layer: caller gets a plain copy, no filter
 *   MIXED     - only part survives: the filtered blob is kept behind a SoftReference
 * Refs that are not VoxelBlobStateReference (full vanilla neighbours) go through a small
 * identity-keyed LRU as before.
 */
public final class FilteredBlobCache {
    private static final byte UNKNOWN = 0, EMPTY = 1, UNCHANGED = 2, MIXED = 3;
    private static final int LAYERS = ChiselLayer.values().length;

    private static final class Info {
        final byte[] status = new byte[LAYERS];
        @SuppressWarnings("unchecked")
        final SoftReference<VoxelBlob>[] mixed = new SoftReference[LAYERS];
    }

    private static final Map<VoxelBlobStateInstance, Info> BY_CONTENT = new WeakHashMap<VoxelBlobStateInstance, Info>(4096);

    /** Full vanilla neighbours: uniform blob of one state id, so the answer depends on (stateID, layer) only. */
    private static final Object OTHER_EMPTY = new Object();
    private static final ConcurrentHashMap<Long, Object> BY_STATE = new ConcurrentHashMap<Long, Object>(1024);
    private static final int OTHER_MAX = Integer.getInteger("cbbakecache.othersize", 4096);
    private static final LinkedHashMap<Key, Object> BY_IDENTITY = new LinkedHashMap<Key, Object>(OTHER_MAX * 4 / 3 + 1, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Object> eldest) {
            return size() > OTHER_MAX;
        }
    };

    private static final class Key {
        final IStateRef ref;
        final ChiselLayer layer;
        final int hash;

        Key(IStateRef ref, ChiselLayer layer) {
            this.ref = ref;
            this.layer = layer;
            this.hash = System.identityHashCode(ref) * 31 + layer.ordinal();
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key && ((Key) o).ref == ref && ((Key) o).layer == layer;
        }
    }

    private FilteredBlobCache() {
    }

    /**
     * Returns the blob of ref filtered for layer, or null when nothing of that layer
     * remains. Same contract as ref.getVoxelBlob() followed by layer.filter(blob)
     * returning false. A MIXED result is shared and must be treated as read-only;
     * the C&B baker only reads it.
     */
    public static VoxelBlob get(IStateRef ref, ChiselLayer layer) {
        if (ref == null) {
            return null;
        }
        if (ref instanceof VoxelBlobStateReference) {
            VoxelBlobStateInstance inst = ((VoxelBlobStateReference) ref).getInstance();
            if (inst != null) {
                return byContent(ref, inst, layer);
            }
        }
        if (ref instanceof BlockStateRefAccessor) {
            return byState(ref, ((BlockStateRefAccessor) ref).cbbakecache$getStateID(), layer);
        }
        return byIdentity(ref, layer);
    }

    private static VoxelBlob byState(IStateRef ref, int stateId, ChiselLayer layer) {
        Long k = ((long) stateId << 8) | layer.ordinal();
        Object v = BY_STATE.get(k);
        if (v == null) {
            VoxelBlob blob = ref.getVoxelBlob();
            v = (blob != null && layer.filter(blob)) ? blob : OTHER_EMPTY;
            Object prev = BY_STATE.putIfAbsent(k, v);
            if (prev != null) {
                v = prev;
            }
        }
        return v == OTHER_EMPTY ? null : (VoxelBlob) v;
    }

    private static VoxelBlob byContent(IStateRef ref, VoxelBlobStateInstance inst, ChiselLayer layer) {
        final int l = layer.ordinal();
        Info info;
        synchronized (BY_CONTENT) {
            info = BY_CONTENT.get(inst);
            if (info == null) {
                info = new Info();
                BY_CONTENT.put(inst, info);
            }
        }
        byte st;
        SoftReference<VoxelBlob> soft;
        synchronized (info) {
            st = info.status[l];
            soft = info.mixed[l];
        }
        if (st == EMPTY) {
            return null;
        }
        if (st == UNCHANGED) {
            return ref.getVoxelBlob();
        }
        if (st == MIXED && soft != null) {
            VoxelBlob b = soft.get();
            if (b != null) {
                return b;
            }
        }
        // UNKNOWN, or a MIXED blob that the GC reclaimed: compute once.
        VoxelBlob blob = ref.getVoxelBlob();
        if (blob == null) {
            return null;
        }
        int before = blob.filled();
        boolean has = layer.filter(blob);
        synchronized (info) {
            if (!has) {
                info.status[l] = EMPTY;
                info.mixed[l] = null;
                return null;
            }
            if (blob.filled() == before) {
                info.status[l] = UNCHANGED;
                info.mixed[l] = null;
            } else {
                info.status[l] = MIXED;
                info.mixed[l] = new SoftReference<VoxelBlob>(blob);
            }
        }
        return blob;
    }

    private static VoxelBlob byIdentity(IStateRef ref, ChiselLayer layer) {
        Key k = new Key(ref, layer);
        Object v;
        synchronized (BY_IDENTITY) {
            v = BY_IDENTITY.get(k);
        }
        if (v == null) {
            VoxelBlob blob = ref.getVoxelBlob();
            v = (blob != null && layer.filter(blob)) ? blob : OTHER_EMPTY;
            synchronized (BY_IDENTITY) {
                BY_IDENTITY.put(k, v);
            }
        }
        return v == OTHER_EMPTY ? null : (VoxelBlob) v;
    }
}
