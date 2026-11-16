package com.cards.deckimport;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageProcessorTest {

    private final ImageProcessor processor = new ImageProcessor();

    private static String problem(ImageProcessor p, byte[] data) {
        return assertThrows(ImageException.class, () -> p.process(data)).getMessage();
    }

    private static boolean contains(byte[] data, String text) {
        return new String(data, StandardCharsets.ISO_8859_1).contains(text);
    }

    @Test
    void jpegPngAndGifAreAcceptedAndComeOutAsJpegOrPng() throws Exception {
        ProcessedImage jpeg = processor.process(TestImages.jpeg(300, 200));
        ProcessedImage png = processor.process(TestImages.png(300, 200));
        ProcessedImage gif = processor.process(TestImages.gif(300, 200));
        ProcessedImage alpha = processor.process(TestImages.pngWithAlpha(300, 200));
        assertEquals("image/jpeg", jpeg.contentType());
        assertEquals("image/jpeg", png.contentType());
        assertEquals("image/jpeg", gif.contentType());
        assertEquals("image/png", alpha.contentType(), "transparency is kept");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg.bytes()));
        assertEquals(300, decoded.getWidth());
        assertEquals(200, decoded.getHeight());
        assertEquals(300, jpeg.width());
    }

    @Test
    void bigImagesAreScaledDownKeepingTheAspectRatio() throws Exception {
        ProcessedImage wide = processor.process(TestImages.jpeg(1600, 800));
        assertEquals(800, wide.width());
        assertEquals(400, wide.height());
        ProcessedImage tall = processor.process(TestImages.png(600, 2400));
        assertEquals(200, tall.width());
        assertEquals(800, tall.height());
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(tall.bytes()));
        assertEquals(800, decoded.getHeight());
    }

    @Test
    void exifAndEveryOtherPartOfTheOriginalAreDropped() {
        byte[] original = TestImages.jpegWithExif(120, 120);
        assertTrue(contains(original, "SECRET-GPS-LOCATION"), "the test file really carries the data");
        ProcessedImage clean = processor.process(original);
        assertFalse(contains(clean.bytes(), "SECRET-GPS-LOCATION"));
        assertFalse(contains(clean.bytes(), "Exif"));
    }

    @Test
    void aScriptAppendedToAValidImageIsDropped() {
        ProcessedImage clean = processor.process(TestImages.pngWithTrailingScript());
        assertFalse(contains(clean.bytes(), "<script>"));
        assertEquals("image/jpeg", clean.contentType());
    }

    @Test
    void theRealContentDecidesNotTheNameOrAnyClaim() {
        assertEquals("only PNG, JPEG and GIF images are accepted", problem(processor, "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8)));
        assertEquals("only PNG, JPEG and GIF images are accepted", problem(processor, "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8)));
        assertEquals("only PNG, JPEG and GIF images are accepted", problem(processor, "GIF".getBytes()));
        assertEquals("only PNG, JPEG and GIF images are accepted", problem(processor, new byte[]{'M', 'Z', 0, 0, 0, 0, 0, 0, 0, 0}));
        assertEquals("only PNG, JPEG and GIF images are accepted", problem(processor, TestImages.write(TestImages.picture(10, 10, false), "bmp")));
        assertEquals("the file is empty", problem(processor, new byte[0]));
    }

    @Test
    void damagedAndTruncatedImagesAreRefused() {
        byte[] jpeg = TestImages.jpeg(200, 200);
        byte[] truncated = java.util.Arrays.copyOf(jpeg, jpeg.length / 3);
        try {
            ProcessedImage partial = processor.process(truncated);          // a cut-off JPEG may still decode partly: then it is re-encoded cleanly
            assertEquals("image/jpeg", partial.contentType());
        } catch (ImageException expected) {
            assertTrue(expected.getMessage().startsWith("the image is damaged"));
        }
        byte[] garbage = jpeg.clone();
        for (int i = 20; i < garbage.length; i += 3) garbage[i] = (byte) (garbage[i] ^ 0x5A);
        // either undecodable (refused) or decodable noise (re-encoded): never an exception of another kind
        try {
            processor.process(garbage);
        } catch (ImageException expected) {
            assertTrue(expected.getMessage().startsWith("the image is damaged"));
        }
        byte[] pngHeaderOnly = java.util.Arrays.copyOf(TestImages.png(50, 50), 40);
        assertTrue(problem(processor, pngHeaderOnly).startsWith("the image is damaged"));
    }

    @Test
    void oversizedFilesAndDecompressionBombsAreRefusedBeforeDecoding() {
        assertEquals("the image is larger than 2 MB", problem(processor, new byte[ImageProcessor.MAX_BYTES + 1]));
        assertTrue(problem(processor, TestImages.pngClaiming(30_000, 30_000)).startsWith("the image is too large in pixels"));
        assertTrue(problem(processor, TestImages.pngClaiming(9_000, 9_000)).startsWith("the image is too large in pixels"), "81 megapixels in a tiny file");
        assertTrue(problem(processor, TestImages.pngClaiming(0, 10)).startsWith("the image is too large in pixels") || problem(processor, TestImages.pngClaiming(0, 10)).startsWith("the image is damaged"));
    }
}
