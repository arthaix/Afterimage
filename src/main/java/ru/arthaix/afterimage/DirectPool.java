package ru.arthaix.afterimage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;

/**
 * Off-heap buffers for section copies waiting on the disk writer or the GPU. As heap arrays these copies outlived young
 * collections and were a quarter of everything the client's collector had to copy (long pauses). Capacities are powers
 * of two, reused without zeroing; beyond POOL_MAX or MAX_SHIFT a released buffer is freed at once.
 *
 * Ownership rule: every acquired buffer is released exactly once, by whoever holds it last.
 */
final class DirectPool {
    private static final int MIN_SHIFT = 16;
    private static final int MAX_SHIFT = 27;
    private static final long POOL_MAX = 256L << 20;
    @SuppressWarnings("unchecked")
    private static final ArrayDeque<ByteBuffer>[] FREE = new ArrayDeque[MAX_SHIFT - MIN_SHIFT + 1];
    private static long pooled;

    static {
        for (int i = 0; i < FREE.length; i++) {
            FREE[i] = new ArrayDeque<ByteBuffer>();
        }
    }

    private DirectPool() {
    }

    private static int shiftFor(int size) {
        int s = MIN_SHIFT;
        while (s < 31 && (1 << s) < size) {
            s++;
        }
        return s;
    }

    /** A native-order buffer with position 0 and limit size. */
    static ByteBuffer acquire(int size) {
        int shift = shiftFor(size);
        if (shift > MAX_SHIFT) {
            return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
        }
        ByteBuffer b;
        synchronized (DirectPool.class) {
            b = FREE[shift - MIN_SHIFT].pollFirst();
            if (b != null) {
                pooled -= b.capacity();
            }
        }
        if (b == null) {
            b = ByteBuffer.allocateDirect(1 << shift).order(ByteOrder.nativeOrder());
        }
        b.clear();
        b.limit(size);
        return b;
    }

    static void release(ByteBuffer b) {
        if (b == null) {
            return;
        }
        int cap = b.capacity();
        if (Integer.bitCount(cap) == 1 && cap >= (1 << MIN_SHIFT) && cap <= (1 << MAX_SHIFT)) {
            synchronized (DirectPool.class) {
                if (pooled + cap <= POOL_MAX) {
                    FREE[Integer.numberOfTrailingZeros(cap) - MIN_SHIFT].addFirst(b);
                    pooled += cap;
                    return;
                }
            }
        }
        free(b);
    }

    private static void free(ByteBuffer b) {
        try {
            sun.misc.Cleaner c = ((sun.nio.ch.DirectBuffer) b).cleaner();
            if (c != null) {
                c.clean();
            }
        } catch (Throwable ignored) {
            // left to the garbage collector
        }
    }

    /** Copy of size bytes of src from its position, as an acquired buffer ready to read (position 0, limit size). */
    static ByteBuffer copyOf(ByteBuffer src, int size) {
        ByteBuffer dst = acquire(size);
        ByteBuffer s = src.duplicate();
        s.limit(s.position() + size);
        dst.put(s);
        dst.flip();
        return dst;
    }
}
