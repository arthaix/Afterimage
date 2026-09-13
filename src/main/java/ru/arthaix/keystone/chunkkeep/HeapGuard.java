package ru.arthaix.keystone.chunkkeep;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;

/**
 * "Heap pressure" for the chunk keep sweep. Runtime.totalMemory - freeMemory is the heap between collections, which on a
 * large G1 heap is routinely above 85% while most of it is garbage; judging by it released half of the kept chunks every
 * sweep and loaded them again on the next flight. The old generation right after its last collection is what is really
 * live. Pressure switches on above -Dchunkkeep.heapGuardPercent (85) of the maximum and off again 10 points lower.
 */
public final class HeapGuard {
    private static MemoryPoolMXBean oldGen;
    private static boolean looked;
    private static boolean pressure;

    private HeapGuard() {
    }

    private static MemoryPoolMXBean oldGen() {
        if (!looked) {
            looked = true;
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                String name = pool.getName();
                if (pool.getType() == MemoryType.HEAP && (name.contains("Old") || name.contains("Tenured"))) {
                    oldGen = pool;
                }
            }
        }
        return oldGen;
    }

    /** Server thread, once per sweep. */
    public static synchronized boolean pressure() {
        double used;
        MemoryPoolMXBean pool = oldGen();
        MemoryUsage after = pool == null ? null : pool.getCollectionUsage();
        long max = Runtime.getRuntime().maxMemory();
        if (after == null || after.getUsed() <= 0L) {
            Runtime rt = Runtime.getRuntime();
            used = (double) (rt.totalMemory() - rt.freeMemory()) / max;
        } else {
            used = (double) after.getUsed() / max;
        }
        if (pressure) {
            pressure = used > Config.HEAP_GUARD - 0.10;
        } else {
            pressure = used > Config.HEAP_GUARD;
        }
        return pressure;
    }
}
