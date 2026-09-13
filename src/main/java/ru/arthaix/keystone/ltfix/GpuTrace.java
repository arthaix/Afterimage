package ru.arthaix.keystone.ltfix;

import java.util.ArrayDeque;

/**
 * Context for frames that stall inside the driver (swapBuffers, makeCurrent): those stacks say only that the GPU was
 * busy. Recorded here, client thread only: every chunk buffer upload of the last few seconds (count, bytes, the largest
 * one) and the GPU-side state, appended to the hitch report by FrameWatch.
 */
public final class GpuTrace {
    private static final long WINDOW_NANOS = 3_000_000_000L;
    private static final ArrayDeque<long[]> UPLOADS = new ArrayDeque<long[]>();
    private static long windowBytes;
    private static long windowLargest;

    private GpuTrace() {
    }

    /** A chunk VertexBuffer received size bytes (Capture.onUpload, client thread). */
    public static synchronized void upload(long size) {
        long now = System.nanoTime();
        UPLOADS.addLast(new long[] {now, size});
        windowBytes += size;
        trim(now);
    }

    private static void trim(long now) {
        long[] u;
        while ((u = UPLOADS.peekFirst()) != null && now - u[0] > WINDOW_NANOS) {
            UPLOADS.pollFirst();
            windowBytes -= u[1];
        }
    }

    public static synchronized String describe() {
        long now = System.nanoTime();
        trim(now);
        long largest = 0L;
        for (long[] u : UPLOADS) {
            if (u[1] > largest) {
                largest = u[1];
            }
        }
        windowLargest = largest;
        String far;
        try {
            far = ru.arthaix.afterimage.Far.gpuState();
        } catch (Throwable t) {
            far = "far n/a";
        }
        return "gpu: chunk uploads in the last 3 s " + UPLOADS.size() + " (" + (windowBytes >> 20) + " MB, largest " + (windowLargest >> 20) + " MB), " + far;
    }
}
