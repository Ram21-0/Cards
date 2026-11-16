package com.cards.deckimport;

import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Turns an uploaded file into a safe image: the type is decided by the real bytes (magic numbers; the file name and the declared type are
 * ignored), the image is fully decoded (a corrupt or hostile file fails here), scaled to at most {@value #MAX_SIDE} px and RE-ENCODED as JPEG
 * (PNG when it has transparency). EXIF data, comments, embedded payloads and anything else in the original are gone.
 */
@Component
public class ImageProcessor {

    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int MAX_SIDE = 800;
    static final long MAX_PIXELS = 25_000_000L;
    static final int MAX_DIMENSION = 12_000;

    public ProcessedImage process(byte[] data) {
        if (data == null || data.length == 0) throw new ImageException("the file is empty");
        if (data.length > MAX_BYTES) throw new ImageException("the image is larger than 2 MB");
        String format = sniff(data);
        if (format == null) throw new ImageException("only PNG, JPEG and GIF images are accepted");
        BufferedImage image = decode(data, format);
        BufferedImage scaled = scale(image);
        return encode(scaled);
    }

    /** "png", "jpeg" or "gif" from the first bytes, else null. */
    static String sniff(byte[] d) {
        if (d.length >= 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G' && d[4] == 0x0D && d[5] == 0x0A && d[6] == 0x1A && d[7] == 0x0A) return "png";
        if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) return "jpeg";
        if (d.length >= 6 && d[0] == 'G' && d[1] == 'I' && d[2] == 'F' && d[3] == '8' && (d[4] == '7' || d[4] == '9') && d[5] == 'a') return "gif";
        return null;
    }

    private static BufferedImage decode(byte[] data, String format) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
            if (!readers.hasNext()) throw new ImageException("this image type cannot be read");
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION || (long) width * height > MAX_PIXELS) {
                    throw new ImageException("the image is too large in pixels (at most " + MAX_PIXELS / 1_000_000 + " megapixels)");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (ImageException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new ImageException("the image is damaged or not a valid " + format.toUpperCase() + " file");
        }
    }

    private static BufferedImage scale(BufferedImage source) {
        int w = source.getWidth(), h = source.getHeight();
        double factor = Math.min(1.0, (double) MAX_SIDE / Math.max(w, h));
        int tw = Math.max(1, (int) Math.round(w * factor)), th = Math.max(1, (int) Math.round(h * factor));
        boolean alpha = source.getColorModel().hasAlpha();
        BufferedImage target = new BufferedImage(tw, th, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            if (!alpha) {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, tw, th);
            }
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, tw, th, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    private static ProcessedImage encode(BufferedImage image) {
        boolean alpha = image.getColorModel().hasAlpha();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (alpha) {
                if (!ImageIO.write(image, "png", out)) throw new ImageException("the image could not be converted");
            } else {
                ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
                try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
                    ImageWriteParam param = writer.getDefaultWriteParam();
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionQuality(0.85f);
                    writer.setOutput(stream);
                    writer.write(null, new IIOImage(image, null, null), param);
                } finally {
                    writer.dispose();
                }
            }
            return new ProcessedImage(out.toByteArray(), alpha ? "image/png" : "image/jpeg", image.getWidth(), image.getHeight());
        } catch (IOException e) {
            throw new ImageException("the image could not be converted");
        }
    }
}
