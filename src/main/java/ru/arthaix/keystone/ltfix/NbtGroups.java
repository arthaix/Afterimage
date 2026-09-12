package ru.arthaix.keystone.ltfix;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Replacement for LittleTile.extractNBTFromGroup with the same output and linear cost. */
public final class NbtGroups {
    private static final String BOXES = "boxes";

    private NbtGroups() {
    }

    /**
     * LittleTiles 1.5.14 does, for every box i of the group: copy = group.copy(); copy.boxes = [group.boxes[i]].
     * The deep copy includes all boxes each time. Here everything except the boxes is deep-copied once and each tile
     * gets its own copy of that plus a one-element list holding the same box element the original code used.
     */
    public static List<NBTTagCompound> split(NBTTagCompound group) {
        NBTTagList boxes = group.func_150295_c(BOXES, 11);
        int n = boxes.func_74745_c();
        List<NBTTagCompound> out = new ArrayList<NBTTagCompound>(n);
        if (n == 0) {
            return out;
        }
        NBTTagCompound base = new NBTTagCompound();
        for (String key : group.func_150296_c()) {
            if (!BOXES.equals(key)) {
                base.func_74782_a(key, group.func_74781_a(key).func_74737_b());
            }
        }
        for (int i = 0; i < n; i++) {
            NBTTagCompound tile = i == n - 1 ? base : base.func_74737_b();
            NBTTagList one = new NBTTagList();
            one.func_74742_a(boxes.func_179238_g(i));
            tile.func_74782_a(BOXES, one);
            out.add(tile);
        }
        return out;
    }
}
