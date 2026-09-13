package ru.arthaix.keystone.ltfix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.client.Minecraft;

/**
 * LittleTiles' RenderUploader (uploadToVBODirectly) allocated a direct buffer the size of a whole chunk layer for every
 * upload, and another one for the read-back of the vanilla geometry it merges with: 100 MB and more per chunk of an
 * imported model, garbage the moment the upload was done, freed only when the collector got to it. That was most of
 * the direct memory still climbing to 17 GB on flights. Both are client-thread only and never outlive their call, so
 * each call site now reuses one buffer, grown to the largest request seen. Off the client thread (never expected) a
 * fresh buffer is returned as before.
 */
public final class DirectScratch {
    private static ByteBuffer upload;
    private static ByteBuffer readback;
    private static final AtomicLong REUSED = new AtomicLong();
    private static final AtomicLong REUSED_BYTES = new AtomicLong();
    private static final AtomicLong GROWN = new AtomicLong();

    private DirectScratch() {
    }

    private static boolean clientThread() {
        try {
            return Minecraft.func_71410_x().func_152345_ab();
        } catch (Throwable t) {
            return false;
        }
    }

    /** As ByteBuffer.allocateDirect(size): position 0, limit size, big-endian order like a fresh buffer. */
    private static ByteBuffer take(ByteBuffer current, int size, boolean forUpload) {
        if (current == null || current.capacity() < size) {
            current = ByteBuffer.allocateDirect(Math.max(size, current == null ? 0 : current.capacity() * 3 / 2)).order(ByteOrder.BIG_ENDIAN);
            GROWN.incrementAndGet();
        } else {
            REUSED.incrementAndGet();
            REUSED_BYTES.addAndGet(size);
        }
        current.clear();
        current.limit(size);
        if (forUpload) {
            upload = current;
        } else {
            readback = current;
        }
        return current;
    }

    /** RenderUploader.uploadRenderData: the buffer the merged layer is written into and uploaded from. */
    public static ByteBuffer forUpload(int size) {
        if (!clientThread()) {
            return ByteBuffer.allocateDirect(size);
        }
        return take(upload, size, true);
    }

    /** RenderUploader.glMapBufferRange: the buffer the current VBO contents are read back into. */
    public static ByteBuffer forReadback(int size) {
        if (!clientThread()) {
            return ByteBuffer.allocateDirect(size);
        }
        return take(readback, size, false);
    }

    public static String stats() {
        return "uploadScratch reused " + REUSED.get() + " " + (REUSED_BYTES.get() >> 20) + "MB grown " + GROWN.get() + " held "
            + (((upload == null ? 0 : upload.capacity()) + (readback == null ? 0 : readback.capacity())) >> 20) + "MB";
    }
}
