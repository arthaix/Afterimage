package ru.arthaix.keystone.cbbakecache;

import java.util.HashMap;

/**
 * A HashMap whose single-key operations are synchronized. Chisels &amp; Bits keeps its face and texture caches in plain
 * static HashMaps (ModelUtil) that every chunk render worker reads and fills at once; concurrent puts corrupt the map
 * (crash "HashMap$Node cannot be cast to HashMap$TreeNode" while tesselating a chiseled block). C&amp;B only calls get,
 * put, containsKey and clear on them, so those (and the other single-key operations) are guarded.
 */
public final class SyncHashMap<K, V> extends HashMap<K, V> {
    private static final long serialVersionUID = 1L;

    public SyncHashMap() {
    }

    public SyncHashMap(java.util.Map<? extends K, ? extends V> initial) {
        super(initial);
    }

    @Override
    public synchronized V get(Object key) {
        return super.get(key);
    }

    @Override
    public synchronized V put(K key, V value) {
        return super.put(key, value);
    }

    @Override
    public synchronized boolean containsKey(Object key) {
        return super.containsKey(key);
    }

    @Override
    public synchronized V remove(Object key) {
        return super.remove(key);
    }

    @Override
    public synchronized void clear() {
        super.clear();
    }

    @Override
    public synchronized int size() {
        return super.size();
    }

    @Override
    public synchronized boolean isEmpty() {
        return super.isEmpty();
    }

    @Override
    public synchronized V putIfAbsent(K key, V value) {
        return super.putIfAbsent(key, value);
    }

    @Override
    public synchronized V getOrDefault(Object key, V defaultValue) {
        return super.getOrDefault(key, defaultValue);
    }
}
