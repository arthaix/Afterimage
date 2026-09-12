package ru.arthaix.keystone.ltfix;

import java.util.zip.Deflater;

/** Implemented on LittleTiles' BufferLink by MixinBufferLink (see GeometryPacker). */
public interface PackableLink {
    /** System.nanoTime() of the last read of the geometry (or of the link's creation). */
    long ltfix$touched();

    /** Packer thread: deflate the geometry if it is still unread since touched; scratch arrays belong to the caller. */
    void ltfix$pack(Deflater deflater, byte[] in, byte[] out);
}
