package ru.arthaix.keystone.ltfix;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * The diagnostic logs are appended to for ever and carry no dates, so sessions of different days ran together and
 * ltfix-hitches.log passed 60 MB. At start-up a log over -Dltfix.logRotateMB (8) is moved aside as "<name>.prev" (the
 * previous one is dropped) and every log starts with a dated session line.
 */
public final class LogRotate {
    private static final long LIMIT = Long.getLong("ltfix.logRotateMB", 8L) << 20;

    private LogRotate() {
    }

    /** Called once per log file when the mod starts. */
    public static File prepare(File file, String what) {
        try {
            if (file.isFile() && file.length() > LIMIT) {
                File prev = new File(file.getPath() + ".prev");
                prev.delete();
                file.renameTo(prev);
            }
            try (PrintWriter w = new PrintWriter(new FileWriter(file, true))) {
                w.println("==== session " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " (" + what + ")");
            }
        } catch (Throwable ignored) {
        }
        return file;
    }
}
