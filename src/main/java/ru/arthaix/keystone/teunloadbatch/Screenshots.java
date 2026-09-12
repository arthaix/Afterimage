package ru.arthaix.keystone.teunloadbatch;

import java.awt.image.RenderedImage;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.imageio.ImageIO;

/** Background PNG writer for screenshots. The file is created at once so the next screenshot in the same second gets its own name. */
public final class Screenshots {
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Screenshot writer");
        t.setDaemon(false);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    });

    private Screenshots() {
    }

    public static boolean write(final RenderedImage image, final String format, final File file) {
        try {
            File dir = file.getParentFile();
            if (dir != null) {
                dir.mkdirs();
            }
            file.createNewFile();
            WRITER.submit(() -> {
                try {
                    ImageIO.write(image, format, file);
                } catch (Throwable t) {
                    System.err.println("[teunloadbatch] screenshot " + file + " not written: " + t);
                }
            });
            return true;
        } catch (Throwable t) {
            try {
                return ImageIO.write(image, format, file);
            } catch (Throwable again) {
                return false;
            }
        }
    }
}
