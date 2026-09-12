import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import java.util.zip.Deflater;

import ru.arthaix.keystone.ltfix.GeometryPacker;

/**
 * Round trip of GeometryPacker on vertex-like data (28-byte BLOCK format quads on a 1/16 grid) and on random bytes:
 * every packed buffer must inflate to the identical bytes; incompressible data must not be packed.
 */
public class GeometryPackerTest {
    public static void main(String[] args) {
        Random r = new Random(42);
        Deflater d = new Deflater(Deflater.BEST_SPEED);
        byte[] in = new byte[1 << 16];
        byte[] out = new byte[1 << 16];
        int[] sizes = {8192, 8204, 65536, 65536 + 28, 1 << 20, (1 << 20) + 112, 7 << 20, 23 << 20};
        long raw = 0, packedTotal = 0, t = 0, u = 0;
        for (int size : sizes) {
            for (int rep = 0; rep < 3; rep++) {
                ByteBuffer src = vertices(r, size, rep == 2 ? 64 : 0);
                long t0 = System.nanoTime();
                byte[] packed = GeometryPacker.deflate(src.duplicate().order(src.order()), size, d, in, out);
                t += System.nanoTime() - t0;
                if (packed == null) {
                    throw new AssertionError("vertex data of " + size + " bytes was not packed");
                }
                long t1 = System.nanoTime();
                ByteBuffer back = GeometryPacker.inflate(packed, size);
                u += System.nanoTime() - t1;
                if (back.position() != 0 || back.limit() != size || back.capacity() != size || back.order() != ByteOrder.nativeOrder()) {
                    throw new AssertionError("bad buffer state " + back);
                }
                for (int i = 0; i < size; i++) {
                    if (back.get(i) != src.get(i)) {
                        throw new AssertionError("byte " + i + " differs for size " + size);
                    }
                }
                raw += size;
                packedTotal += packed.length;
            }
        }
        // a view whose capacity exceeds length (BufferBuilder buffers are sized for the quads counted before culling)
        ByteBuffer big = vertices(r, 200_000, 0);
        byte[] part = GeometryPacker.deflate(big.duplicate(), 150_000, d, in, out);
        ByteBuffer partBack = GeometryPacker.inflate(part, 150_000);
        for (int i = 0; i < 150_000; i++) {
            if (partBack.get(i) != big.get(i)) {
                throw new AssertionError("partial length differs at " + i);
            }
        }
        ByteBuffer noise = ByteBuffer.allocateDirect(1 << 20);
        byte[] b = new byte[1 << 20];
        r.nextBytes(b);
        noise.put(b).flip();
        if (GeometryPacker.deflate(noise.duplicate(), 1 << 20, d, in, out) != null) {
            throw new AssertionError("random bytes were packed");
        }
        if (GeometryPacker.deflate(ByteBuffer.allocateDirect(100), 8192, d, in, out) != null) {
            throw new AssertionError("a buffer shorter than length was packed");
        }
        System.out.printf("OK: %d MB -> %d MB (%.1fx), deflate %.0f MB/s, inflate %.0f MB/s%n", raw >> 20, packedTotal >> 20,
            (double) raw / packedTotal, raw / 1048576.0 / (t / 1e9), raw / 1048576.0 / (u / 1e9));
    }

    /** Quads of 4 vertices: xyz float on a 1/16 grid, RGBA, UV float, lightmap short pair. */
    private static ByteBuffer vertices(Random r, int size, int noiseEvery) {
        ByteBuffer b = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
        int quad = 0;
        while (b.remaining() >= 28) {
            float x = r.nextInt(16 * 16) / 16f, y = r.nextInt(16 * 16) / 16f, z = r.nextInt(16 * 16) / 16f;
            float u0 = r.nextInt(64) / 1024f, v0 = r.nextInt(64) / 1024f;
            int color = r.nextInt(4) == 0 ? 0xFFCCCCCC : 0xFFFFFFFF;
            for (int v = 0; v < 4 && b.remaining() >= 28; v++) {
                b.putFloat(x + (v == 1 || v == 2 ? 0.0625f : 0f)).putFloat(y).putFloat(z + (v >= 2 ? 0.0625f : 0f));
                b.putInt(color);
                b.putFloat(u0 + (v == 1 || v == 2 ? 0.015625f : 0f)).putFloat(v0 + (v >= 2 ? 0.015625f : 0f));
                b.putShort((short) 240).putShort((short) (noiseEvery > 0 && quad % noiseEvery == 0 ? r.nextInt(240) : 240));
            }
            quad++;
        }
        while (b.hasRemaining()) {
            b.put((byte) 0);
        }
        b.flip();
        return b;
    }
}
