import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import ru.arthaix.keystone.ltfix.NbtGroups;

/** NbtGroups.split against the LittleTiles 1.5.14 algorithm (transcribed from its bytecode). */
public class NbtGroupsTest {
    static List<NBTTagCompound> original(NBTTagCompound nbt) {
        List<NBTTagCompound> tags = new ArrayList<NBTTagCompound>();
        NBTTagList list = nbt.func_150295_c("boxes", 11);
        for (int i = 0; i < list.func_74745_c(); i++) {
            NBTTagCompound copy = nbt.func_74737_b();
            NBTTagList small = new NBTTagList();
            small.func_74742_a(list.func_179238_g(i));
            copy.func_74782_a("boxes", small);
            tags.add(copy);
        }
        return tags;
    }

    static NBTTagCompound randomGroup(Random r, int boxes) {
        NBTTagCompound g = new NBTTagCompound();
        g.func_74778_a("block", "littletiles:lttilesconcrete:" + r.nextInt(16));
        if (r.nextBoolean()) g.func_74768_a("color", r.nextInt());
        if (r.nextBoolean()) g.func_74757_a("invisible", r.nextBoolean());
        if (r.nextInt(3) == 0) {
            NBTTagCompound sub = new NBTTagCompound();
            sub.func_74768_a("light", r.nextInt(16));
            NBTTagList l = new NBTTagList();
            l.func_74742_a(new NBTTagString("x" + r.nextInt()));
            sub.func_74782_a("names", l);
            g.func_74782_a("extra", sub);
        }
        NBTTagList list = new NBTTagList();
        for (int i = 0; i < boxes; i++) {
            int[] b = new int[6 + r.nextInt(4)];
            for (int k = 0; k < b.length; k++) b[k] = r.nextInt(64);
            list.func_74742_a(new NBTTagIntArray(b));
        }
        if (boxes > 0 || r.nextBoolean()) g.func_74782_a("boxes", list);
        return g;
    }

    public static void main(String[] args) {
        Random r = new Random(42);
        int groups = 0;
        for (int it = 0; it < 20000; it++) {
            NBTTagCompound g = randomGroup(r, r.nextInt(12));
            String before = g.toString();
            List<NBTTagCompound> a = original(g);
            List<NBTTagCompound> b = NbtGroups.split(g);
            if (!g.toString().equals(before)) throw new AssertionError("input changed");
            if (!a.equals(b)) throw new AssertionError("different output for " + g);
            // tiles must not share mutable non-box data with each other or with the group
            if (b.size() > 1 && g.func_74764_b("extra")) {
                b.get(0).func_74775_l("extra").func_74768_a("light", 99);
                if (b.get(1).func_74775_l("extra").func_74762_e("light") == 99 || g.func_74775_l("extra").func_74762_e("light") == 99) {
                    throw new AssertionError("shared mutable data");
                }
            }
            groups++;
        }
        NBTTagCompound big = randomGroup(r, 3000);
        long t0 = System.nanoTime();
        int n1 = original(big).size();
        long t1 = System.nanoTime();
        int n2 = NbtGroups.split(big).size();
        long t2 = System.nanoTime();
        System.out.println("OK " + groups + " random groups identical; 3000-box group: original " + (t1 - t0) / 1000000 + " ms, split " + (t2 - t1) / 1000000 + " ms (" + n1 + "/" + n2 + " tiles)");
    }
}
