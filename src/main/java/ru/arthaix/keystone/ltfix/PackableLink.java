package ru.arthaix.keystone.ltfix;

import java.util.zip.Deflater;

/** Implemented on LittleTiles' BufferLink by MixinBufferLink (see GeometryPacker). */
public interface PackableLink {
    /** System.nanoTime() of the last read of the geometry (or of the link's creation). */
    long ltfix$touched();

    /** Packer thread: deflate the geometry if it is still unread since touched; true when it was packed. */
    boolean ltfix$pack(Deflater deflater, byte[] in, byte[] out);

    /** GeometryPacker's token for this link, null for small links. */
    Object ltfix$token();

    /** Whether the link holds geometry, packed or not, without unpacking it. */
    boolean ltfix$hasGeometry();

    /** The link was replaced or emptied in its tile entity. */
    void ltfix$release();
}
