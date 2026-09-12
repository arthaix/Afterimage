package ru.arthaix.keystone.ltfix;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * Accumulated stop-the-world garbage collection time of this JVM in ms, to attribute long frames and ticks. Shenandoah
 * reports its concurrent work as a separate "Shenandoah Cycles" collector; that time does not stop the game and is left
 * out, only "Shenandoah Pauses" count. G1 and the others report pauses only.
 */
public final class GcTime {
    private static final List<GarbageCollectorMXBean> BEANS = new ArrayList<GarbageCollectorMXBean>();

    static {
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (!b.getName().contains("Cycles")) {
                BEANS.add(b);
            }
        }
    }

    private GcTime() {
    }

    public static long totalMs() {
        long t = 0L;
        for (GarbageCollectorMXBean b : BEANS) {
            long c = b.getCollectionTime();
            if (c > 0L) {
                t += c;
            }
        }
        return t;
    }
}
