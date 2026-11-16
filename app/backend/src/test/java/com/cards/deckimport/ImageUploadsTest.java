package com.cards.deckimport;

import com.cards.common.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageUploadsTest {

    private static List<NamedBytes> one(byte[] zip) {
        return List.of(new NamedBytes("images.zip", zip));
    }

    private static String problem(List<NamedBytes> files) {
        return assertThrows(BadRequestException.class, () -> ImageUploads.collect(files)).getMessage();
    }

    @Test
    void loneFilesAreKeyedByLowerCaseBaseName() {
        byte[] a = TestImages.png(10, 10), b = TestImages.jpeg(10, 10);
        Map<String, byte[]> files = ImageUploads.collect(List.of(new NamedBytes("C:\\Users\\me\\Photos\\Sachin.PNG", a), new NamedBytes("kapil.jpg", b)));
        assertEquals(2, files.size());
        assertArrayEquals(a, files.get("sachin.png"));
        assertArrayEquals(b, files.get("kapil.jpg"));
        assertEquals("sachin.png", ImageUploads.keyOf("  photos/Sachin.PNG "));
    }

    @Test
    void aZipIsUnpackedInMemoryByBaseNameWhateverFolderItIsIn() {
        byte[] a = TestImages.png(10, 10), b = TestImages.png(12, 12);
        Map<String, byte[]> files = ImageUploads.collect(one(TestImages.zip("cards/front/A.png", a, "B.png", b, "cards/", new byte[0])));
        assertEquals(java.util.Set.of("a.png", "b.png"), files.keySet());
        assertArrayEquals(a, files.get("a.png"));
    }

    @Test
    void pathTraversalHiddenAndMacOsEntriesAreIgnored() {
        byte[] img = TestImages.png(10, 10);
        Map<String, byte[]> files = ImageUploads.collect(one(TestImages.zip(
                "../../etc/passwd.png", img, "..\\..\\windows\\evil.png", img, "/abs/path/evil2.png", img, "ok/../../up.png", img,
                "__MACOSX/._A.png", img, ".hidden.png", img, "good.png", img)));
        assertEquals(java.util.Set.of("good.png"), files.keySet());
    }

    @Test
    void aZipBombIsStoppedByTheTotalUnpackedLimit() {
        byte[] bomb = TestImages.zipBomb(40);
        assertTrue(bomb.length < 200_000, "the archive is small: " + bomb.length);
        assertTrue(problem(one(bomb)).startsWith("The ZIP is larger than 30 MB when unpacked"));
    }

    @Test
    void tooManyEntriesAreRefused() {
        List<Object> entries = new ArrayList<>();
        for (int i = 0; i < ImageUploads.MAX_ENTRIES + 1; i++) {
            entries.add(".f" + i);
            entries.add(new byte[]{1});
        }
        assertEquals("The ZIP has more than 300 entries", problem(one(TestImages.zip(entries.toArray()))));
    }

    @Test
    void tooManyKeptFilesAndDuplicateNamesAreRefused() {
        List<NamedBytes> files = new ArrayList<>();
        for (int i = 0; i < ImageUploads.MAX_FILES + 1; i++) files.add(new NamedBytes("f" + i + ".png", new byte[]{1}));
        assertEquals("At most 200 image files per upload", problem(files));
        assertEquals("Two uploaded files are called 'a.png'", problem(List.of(new NamedBytes("a.png", new byte[]{1}), new NamedBytes("dir/A.PNG", new byte[]{2}))));
        assertEquals("Two uploaded files are called 'a.png'", problem(one(TestImages.zip("x/a.png", new byte[]{1}, "y/a.png", new byte[]{2}))));
    }

    @Test
    void aFileOverTwoMegabytesInsideAZipIsLeftOutAndNotAnError() {
        byte[] big = new byte[ImageProcessor.MAX_BYTES + 10];
        Map<String, byte[]> files = ImageUploads.collect(one(TestImages.zip("big.png", big, "small.png", TestImages.png(5, 5))));
        assertEquals(java.util.Set.of("small.png"), files.keySet());
    }

    @Test
    void aDamagedZipIsRefusedAndEmptyInputGivesNothing() {
        byte[] zip = TestImages.zip("a.png", TestImages.png(30, 30));
        byte[] damaged = java.util.Arrays.copyOf(zip, zip.length / 2);
        assertEquals("The ZIP file is damaged", problem(one(damaged)));
        assertTrue(ImageUploads.collect(List.of()).isEmpty());
        assertTrue(ImageUploads.collect(null).isEmpty());
        assertTrue(ImageUploads.collect(List.of(new NamedBytes("x.png", new byte[0]))).isEmpty());
    }

    @Test
    void nonImagesInsideAZipAreKeptHereAndRejectedOnlyWhenACardUsesThem() {
        Map<String, byte[]> files = ImageUploads.collect(one(TestImages.zip("notes.txt", "hello".getBytes(), "a.png", TestImages.png(5, 5))));
        assertEquals(java.util.Set.of("notes.txt", "a.png"), files.keySet());
    }
}
