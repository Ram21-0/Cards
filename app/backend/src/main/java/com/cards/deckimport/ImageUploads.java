package com.cards.deckimport;

import com.cards.common.BadRequestException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The files of an upload, by lower-case base name. A ZIP is unpacked in memory only (nothing is ever written to disk, so there is no path to
 * traverse), with limits against zip bombs: entry count, total uncompressed size, size per file. Directories, hidden/"__MACOSX" junk and
 * entries with ".." in their path are ignored; only the base name of an entry counts.
 */
public final class ImageUploads {

    public static final int MAX_ENTRIES = 300;
    public static final long MAX_TOTAL_BYTES = 30L * 1024 * 1024;
    public static final int MAX_FILES = 200;

    private ImageUploads() {
    }

    public static Map<String, byte[]> collect(List<NamedBytes> files) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        long[] total = {0};
        for (NamedBytes file : files == null ? List.<NamedBytes>of() : files) {
            if (file == null || file.bytes() == null || file.bytes().length == 0) continue;
            if (isZip(file.bytes())) unzip(file.bytes(), result, total);
            else add(result, file.name(), file.bytes(), total);
        }
        return result;
    }

    static boolean isZip(byte[] d) {
        return d.length >= 4 && d[0] == 'P' && d[1] == 'K' && d[2] == 3 && d[3] == 4;
    }

    private static void unzip(byte[] zip, Map<String, byte[]> into, long[] total) {
        int entries = 0;
        long unpacked = 0;                                                             // everything the ZIP unpacks to, kept or not
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new BadRequestException("The ZIP has more than " + MAX_ENTRIES + " entries");
                boolean keep = !entry.isDirectory() && acceptableEntry(entry.getName());
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16 * 1024];
                int read;
                long size = 0;
                while ((read = in.read(buffer)) > 0) {
                    size += read;
                    unpacked += read;
                    if (unpacked > MAX_TOTAL_BYTES) throw new BadRequestException("The ZIP is larger than " + MAX_TOTAL_BYTES / (1024 * 1024) + " MB when unpacked");
                    if (keep && size <= ImageProcessor.MAX_BYTES) out.write(buffer, 0, read);
                }
                if (keep && size <= ImageProcessor.MAX_BYTES) add(into, entry.getName(), out.toByteArray(), total);   // a bigger file is left out: it would not be accepted anyway
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new BadRequestException("The ZIP file is damaged");
        }
    }

    private static boolean acceptableEntry(String name) {
        String n = name.replace('\\', '/');
        if (n.startsWith("/") || n.contains("../") || n.equals("..") || n.endsWith("/..")) return false;      // path traversal: ignored
        if (n.startsWith("__MACOSX/") || n.contains("/__MACOSX/")) return false;
        return !baseName(n).startsWith(".");
    }

    static String baseName(String name) {
        String n = name.replace('\\', '/');
        return n.substring(n.lastIndexOf('/') + 1);
    }

    private static void add(Map<String, byte[]> into, String name, byte[] bytes, long[] total) {
        String key = baseName(name == null ? "" : name).trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return;
        total[0] += bytes.length;
        if (total[0] > MAX_TOTAL_BYTES) throw new BadRequestException("The uploaded images are larger than " + MAX_TOTAL_BYTES / (1024 * 1024) + " MB together");
        if (into.containsKey(key)) throw new BadRequestException("Two uploaded files are called '" + key + "'");
        if (into.size() >= MAX_FILES) throw new BadRequestException("At most " + MAX_FILES + " image files per upload");
        into.put(key, bytes);
    }

    /** The key a CSV cell uses to find its file: base name, lower case. */
    public static String keyOf(String cell) {
        return baseName(cell).trim().toLowerCase(Locale.ROOT);
    }
}
