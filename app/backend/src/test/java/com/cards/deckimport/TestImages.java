package com.cards.deckimport;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Real image and ZIP bytes for the tests. */
final class TestImages {

    private TestImages() {
    }

    static byte[] write(BufferedImage image, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, format, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static BufferedImage picture(int w, int h, boolean alpha) {
        BufferedImage img = new BufferedImage(w, h, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(200, 30, 30));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(20, 20, 220));
        g.fillOval(w / 4, h / 4, Math.max(1, w / 2), Math.max(1, h / 2));
        g.dispose();
        return img;
    }

    static byte[] png(int w, int h) {
        return write(picture(w, h, false), "png");
    }

    static byte[] pngWithAlpha(int w, int h) {
        return write(picture(w, h, true), "png");
    }

    static byte[] jpeg(int w, int h) {
        return write(picture(w, h, false), "jpg");
    }

    static byte[] gif(int w, int h) {
        return write(picture(w, h, false), "gif");
    }

    /** A JPEG with an APP1 "Exif" segment (and a GPS-looking marker) inserted right after the start of image. */
    static byte[] jpegWithExif(int w, int h) {
        byte[] jpeg = jpeg(w, h);
        byte[] exif = ("Exif\0\0SECRET-GPS-LOCATION-12.34,56.78").getBytes();
        int length = exif.length + 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(0xFF);
        out.write(0xE1);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.write(exif, 0, exif.length);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    /** A valid PNG with a script and a comment appended after the end of the image (a "polyglot"). */
    static byte[] pngWithTrailingScript() {
        byte[] png = png(40, 40);
        byte[] trailer = "<script>alert('xss')</script>".getBytes();
        byte[] out = new byte[png.length + trailer.length];
        System.arraycopy(png, 0, out, 0, png.length);
        System.arraycopy(trailer, 0, out, png.length, trailer.length);
        return out;
    }

    /** Just the signature and an IHDR claiming this size: enough for a reader to see the dimensions, nothing to decode. */
    static byte[] pngClaiming(int w, int h) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        out.write(sig, 0, sig.length);
        byte[] ihdr = new byte[13];
        put(ihdr, 0, w);
        put(ihdr, 4, h);
        ihdr[8] = 8;
        ihdr[9] = 2;
        out.write(0);
        out.write(0);
        out.write(0);
        out.write(13);
        byte[] type = {'I', 'H', 'D', 'R'};
        out.write(type, 0, 4);
        out.write(ihdr, 0, 13);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(type);
        crc.update(ihdr);
        long c = crc.getValue();
        out.write((int) (c >> 24));
        out.write((int) (c >> 16));
        out.write((int) (c >> 8));
        out.write((int) c);
        return out.toByteArray();
    }

    private static void put(byte[] b, int at, int v) {
        b[at] = (byte) (v >> 24);
        b[at + 1] = (byte) (v >> 16);
        b[at + 2] = (byte) (v >> 8);
        b[at + 3] = (byte) v;
    }

    static byte[] zip(Object... namesAndContents) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                for (int i = 0; i < namesAndContents.length; i += 2) {
                    String name = (String) namesAndContents[i];
                    byte[] content = (byte[]) namesAndContents[i + 1];
                    zip.putNextEntry(new ZipEntry(name));
                    zip.write(content);
                    zip.closeEntry();
                }
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A ZIP whose single entry unpacks to {@code megabytes} MB of zeros while the archive itself stays tiny. */
    static byte[] zipBomb(int megabytes) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry("bomb.bin"));
                byte[] block = new byte[1024 * 1024];
                for (int i = 0; i < megabytes; i++) zip.write(block);
                zip.closeEntry();
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
