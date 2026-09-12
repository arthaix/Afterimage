package ru.arthaix.keystone.ltfix;

import java.awt.image.BufferedImage;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.SinglePixelPackedSampleModel;
import java.awt.image.WritableRaster;

/**
 * BufferedImage.getRGB for the two image layouts map textures come in, read straight from the pixel array. For a PNG
 * decoded by ImageIO (TYPE_4BYTE_ABGR) the JDK converts every pixel through ComponentColorModel, about 100 ns each:
 * a 512x512 JourneyMap region took tens of milliseconds on the client thread. Anything else uses getRGB itself.
 */
public final class FastPixels {
    private FastPixels() {
    }

    public static int[] getRGB(BufferedImage img, int sx, int sy, int w, int h, int[] out, int off, int scan) {
        WritableRaster r = img.getRaster();
        if (w <= 0 || h <= 0 || sx < 0 || sy < 0 || sx + w > img.getWidth() || sy + h > img.getHeight() || r.getParent() != null
            || r.getSampleModelTranslateX() != 0 || r.getSampleModelTranslateY() != 0) {
            return img.getRGB(sx, sy, w, h, out, off, scan);
        }
        if (out == null) {
            out = new int[off + h * scan];
        }
        DataBuffer db = r.getDataBuffer();
        int type = img.getType();
        if (type == BufferedImage.TYPE_4BYTE_ABGR && db instanceof DataBufferByte && db.getNumBanks() == 1
            && r.getSampleModel() instanceof ComponentSampleModel) {
            ComponentSampleModel sm = (ComponentSampleModel) r.getSampleModel();
            int[] bo = sm.getBandOffsets();
            int[] bi = sm.getBankIndices();
            if (sm.getNumBands() != 4 || bo[0] != 3 || bo[1] != 2 || bo[2] != 1 || bo[3] != 0 || bi[0] != 0 || bi[1] != 0
                || bi[2] != 0 || bi[3] != 0 || sm.getPixelStride() != 4) {
                return img.getRGB(sx, sy, w, h, out, off, scan);
            }
            byte[] d = ((DataBufferByte) db).getData();
            int stride = sm.getScanlineStride();
            int base = db.getOffset();
            for (int y = 0; y < h; y++) {
                int p = base + (sy + y) * stride + sx * 4;
                int o = off + y * scan;
                for (int x = 0; x < w; x++, p += 4) {
                    out[o + x] = (d[p] & 255) << 24 | (d[p + 3] & 255) << 16 | (d[p + 2] & 255) << 8 | (d[p + 1] & 255);
                }
            }
            return out;
        }
        if (type == BufferedImage.TYPE_INT_ARGB && db instanceof DataBufferInt && db.getNumBanks() == 1
            && r.getSampleModel() instanceof SinglePixelPackedSampleModel) {
            SinglePixelPackedSampleModel sm = (SinglePixelPackedSampleModel) r.getSampleModel();
            int[] d = ((DataBufferInt) db).getData();
            int stride = sm.getScanlineStride();
            int base = db.getOffset();
            for (int y = 0; y < h; y++) {
                System.arraycopy(d, base + (sy + y) * stride + sx, out, off + y * scan, w);
            }
            return out;
        }
        return img.getRGB(sx, sy, w, h, out, off, scan);
    }
}
