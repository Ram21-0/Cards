package com.cards.deckimport;

/** Lets tests outside the package build image bytes. */
public final class TestImagesAccess {

    private TestImagesAccess() {
    }

    public static byte[] jpeg(int w, int h) {
        return TestImages.jpeg(w, h);
    }

    public static byte[] png(int w, int h) {
        return TestImages.png(w, h);
    }

    public static byte[] zip(Object... namesAndContents) {
        return TestImages.zip(namesAndContents);
    }
}
