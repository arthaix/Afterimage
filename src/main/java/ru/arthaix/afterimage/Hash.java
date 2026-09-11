package ru.arthaix.afterimage;

import java.nio.ByteBuffer;

/**
 * Two 64-bit fingerprints of a vertex buffer in one pass:
 *  exact    - order-dependent, equal only for byte-identical buffers;
 *  multiset - order-independent over quads, equal when the same quads are present in any
 *             order (translucent resorting, merge-order differences).
 */
public final class Hash {
    private Hash() {
    }

    private static long fmix(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }

    /** Returns {exact, multiset}. Uses absolute gets: the buffer's position is untouched. */
    public static long[] of(ByteBuffer buf, int vertexSize) {
        int pos = buf.position();
        int lim = buf.limit();
        int len = lim - pos;
        if (len <= 0) {
            return new long[] {0L, 0L};
        }
        int quad = vertexSize > 0 ? vertexSize * 4 : len;
        if (len % quad != 0) {
            quad = len;
        }
        long exact = 0x9E3779B97F4A7C15L ^ len;
        long sumA = 0L;
        long sumB = 0L;
        for (int q = pos; q < lim; q += quad) {
            int end = q + quad;
            long h = 0x87C37B91114253D5L ^ quad;
            int i = q;
            for (; i + 8 <= end; i += 8) {
                h = Long.rotateLeft(h ^ (buf.getLong(i) * 0x4CF5AD432745937FL), 27) * 5 + 0x52DCE729L;
            }
            for (; i < end; i++) {
                h = Long.rotateLeft(h ^ ((buf.get(i) & 0xFFL) * 0x4CF5AD432745937FL), 11) * 7 + 0x38495AB5L;
            }
            h = fmix(h);
            exact = Long.rotateLeft(exact * 0x100000001B3L ^ h, 17);
            sumA += h;
            sumB += fmix(h ^ 0xC2B2AE3D27D4EB4FL);
        }
        return new long[] {fmix(exact), fmix(sumA ^ Long.rotateLeft(sumB, 29) ^ len)};
    }
}
