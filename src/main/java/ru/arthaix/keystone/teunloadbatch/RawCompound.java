package ru.arthaix.keystone.teunloadbatch;

import java.io.DataOutput;
import java.io.IOException;

/** Implemented on NBTTagCompound by MixinNBTTagCompoundRaw. */
public interface RawCompound {
    /** Body bytes (entries and the end tag) this compound writes instead of its own entries. */
    void teunloadbatch$setRaw(byte[] body);

    /** Writes the compound's entries and end tag, as NBTTagCompound.write does. */
    void teunloadbatch$writeBody(DataOutput out) throws IOException;
}
