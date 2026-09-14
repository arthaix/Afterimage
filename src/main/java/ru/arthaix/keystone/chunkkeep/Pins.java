package ru.arthaix.keystone.chunkkeep;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Set of chunks (overworld) that stay loaded for the whole server lifetime.
 * Loaded from config/chunkkeep-pins.txt: one "chunkX chunkZ" pair per line,
 * '#' starts a comment.
 */
public final class Pins {
    private static volatile Set<Long> PINNED = new HashSet<Long>();
    private static volatile int[][] LIST = new int[0][];

    private Pins() {
    }

    public static long key(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }

    public static boolean isPinned(int x, int z) {
        return PINNED.contains(key(x, z));
    }

    public static int count() {
        return LIST.length;
    }

    public static int[][] list() {
        return LIST;
    }

    public static int load(File file) throws IOException {
        Set<Long> set = new HashSet<Long>(32768);
        int bad = 0;
        java.util.List<int[]> list = new java.util.ArrayList<int[]>(32768);
        if (file.isFile()) {
            BufferedReader r = new BufferedReader(new FileReader(file));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    int hash = line.indexOf('#');
                    if (hash >= 0) {
                        line = line.substring(0, hash);
                    }
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    String[] p = line.split("\\s+");
                    if (p.length < 2) {
                        continue;
                    }
                    int x;
                    int z;
                    try {
                        x = Integer.parseInt(p[0]);
                        z = Integer.parseInt(p[1]);
                    } catch (NumberFormatException e) {
                        // one bad line used to abort the whole file and leave every pinned chunk unprotected
                        bad++;
                        continue;
                    }
                    if (set.add(key(x, z))) {
                        list.add(new int[] {x, z});
                    }
                }
            } finally {
                r.close();
            }
        }
        PINNED = set;
        LIST = list.toArray(new int[0][]);
        if (bad > 0) {
            System.out.println("[chunkkeep] " + bad + " unreadable line(s) in " + file.getName() + " skipped");
        }
        return LIST.length;
    }
}
