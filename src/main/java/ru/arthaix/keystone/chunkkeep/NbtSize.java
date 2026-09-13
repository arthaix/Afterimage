package ru.arthaix.keystone.chunkkeep;

import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import ru.arthaix.keystone.teunloadbatch.RawCompound;

/** Bytes a tag takes on the wire, counted by walking it instead of writing it. */
public final class NbtSize {
    private static volatile Method write;

    private NbtSize() {
    }

    /** PacketBuffer.writeCompoundTag: type byte, empty name, body. */
    public static long root(NBTTagCompound tag) {
        return tag == null ? 1L : 3L + body(tag);
    }

    private static long body(NBTTagCompound c) {
        if (c instanceof RawCompound) {
            byte[] raw = ((RawCompound) c).teunloadbatch$raw();
            if (raw != null) {
                return raw.length;
            }
        }
        long n = 1L;
        for (String key : c.func_150296_c()) {
            n += 1L + utf(key) + payload(c.func_74781_a(key));
        }
        return n;
    }

    private static long payload(NBTBase b) {
        if (b == null) {
            return 0L;
        }
        switch (b.func_74732_a()) {
            case 1:
                return 1L;
            case 2:
                return 2L;
            case 3:
            case 5:
                return 4L;
            case 4:
            case 6:
                return 8L;
            case 7:
                return 4L + ((NBTTagByteArray) b).func_150292_c().length;
            case 8:
                return utf(((NBTTagString) b).func_150285_a_());
            case 9: {
                NBTTagList list = (NBTTagList) b;
                long n = 5L;
                for (int i = 0, count = list.func_74745_c(); i < count; i++) {
                    n += payload(list.func_179238_g(i));
                }
                return n;
            }
            case 10:
                return body((NBTTagCompound) b);
            case 11:
                return 4L + 4L * ((NBTTagIntArray) b).func_150302_c().length;
            default:
                return written(b);
        }
    }

    /** DataOutput.writeUTF: length prefix plus modified UTF-8. */
    private static long utf(String s) {
        long n = 2L;
        for (int i = 0, len = s.length(); i < len; i++) {
            char ch = s.charAt(i);
            n += ch >= 1 && ch <= 0x7F ? 1 : ch > 0x7FF ? 3 : 2;
        }
        return n;
    }

    /** Long arrays and anything else: written into a counter. */
    private static long written(NBTBase b) {
        try {
            Method m = write;
            if (m == null) {
                m = NBTBase.class.getDeclaredMethod("func_74734_a", DataOutput.class);
                m.setAccessible(true);
                write = m;
            }
            Counter counter = new Counter();
            m.invoke(b, new DataOutputStream(counter));
            return counter.n;
        } catch (Throwable t) {
            // unknown: count it as big, which at worst sends its chunk in more packets than needed
            return 1L << 20;
        }
    }

    private static final class Counter extends OutputStream {
        long n;

        @Override
        public void write(int b) {
            n++;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            n += len;
        }
    }
}
