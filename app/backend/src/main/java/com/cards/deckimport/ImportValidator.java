package com.cards.deckimport;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks a parsed CSV against the stat specs and builds the cards. EVERY problem is collected (up to {@value #MAX_ERRORS}) so a preview can show
 * them all at once. Nothing here touches the database or the disk.
 */
public final class ImportValidator {

    public static final int MAX_ERRORS = 50;
    public static final int MAX_NAME_LENGTH = 60;
    public static final int MAX_BRAND_LENGTH = 30;
    public static final int MAX_URL_LENGTH = 500;
    private static final Pattern NUMBER = Pattern.compile("-?\\d{1,12}(\\.\\d{1,12})?");

    private ImportValidator() {
    }

    public record Options(int minCards, int maxCards) {
        /** What a user's own upload must satisfy. */
        public static final Options USER = new Options(8, 100);
        /** The admin's import for a requested deck. */
        public static final Options ADMIN = new Options(8, 500);
    }

    /** {@code specs} with their value types settled (INT or DECIMAL), the cards, and every error found. */
    public record Result(List<StatSpec> specs, List<ImportedCard> cards, List<String> errors, int errorCount) {
        public boolean ok() {
            return errorCount == 0;
        }
    }

    private static final class Errors {
        final List<String> shown = new ArrayList<>();
        int count;

        void add(String message) {
            count++;
            if (shown.size() < MAX_ERRORS) shown.add(message);
        }
    }

    /**
     * @param uploads  uploaded image files by lower-case base name (see {@link ImageUploads}); may be empty
     * @param pictures decodes an uploaded file (see {@link ImageProcessor})
     */
    public static Result validate(StatImportParser.Table table, List<StatSpec> specs, Map<String, byte[]> uploads, ImageProcessor pictures, Options options) {
        Errors errors = new Errors();
        if (!table.header().contains("name")) {
            errors.add("The CSV needs a 'name' column");
            return new Result(specs, List.of(), errors.shown, errors.count);
        }
        for (StatSpec spec : specs) {
            if (!table.header().contains(spec.column())) errors.add("The CSV has no column '" + spec.column() + "'");
        }
        List<Map<String, String>> rows = table.rows();
        if (rows.size() < options.minCards()) errors.add("A deck needs at least " + options.minCards() + " cards (the CSV has " + rows.size() + ")");
        if (rows.size() > options.maxCards()) errors.add("A deck can have at most " + options.maxCards() + " cards (the CSV has " + rows.size() + ")");
        if (errors.count > 0) return new Result(specs, List.of(), errors.shown, errors.count);

        List<ImportedCard> cards = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Map<String, Map<Integer, String>> ranks = new HashMap<>();
        Map<String, Object> imageCache = new HashMap<>();                                   // file key -> ProcessedImage, or the error text
        boolean[] whole = new boolean[specs.size()];
        java.util.Arrays.fill(whole, true);
        int rowNumber = 0;
        for (Map<String, String> row : rows) {
            rowNumber++;
            String name = Text.clean(row.get("name"));
            String where = "Row " + rowNumber + (name.isEmpty() ? "" : " (" + name + ")");
            int before = errors.count;

            if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) errors.add(where + ": name must be 1 to " + MAX_NAME_LENGTH + " characters");
            else if (!names.add(name.toLowerCase(Locale.ROOT))) errors.add(where + ": duplicate name");

            Map<String, BigDecimal> stats = new LinkedHashMap<>();
            for (int i = 0; i < specs.size(); i++) {
                StatSpec spec = specs.get(i);
                BigDecimal value = number(row.get(spec.column()), spec, where, errors);
                if (value == null) continue;
                stats.put(spec.key(), value);
                if (value.stripTrailingZeros().scale() > 0) whole[i] = false;
                if (spec.key().equals("rank") && value.stripTrailingZeros().scale() <= 0) {
                    String owner = ranks.computeIfAbsent("rank", k -> new HashMap<>()).putIfAbsent(value.intValue(), name);
                    if (owner != null) errors.add(where + ": rank " + value.toBigInteger() + " is already used by " + owner + " (ranks must be unique)");
                }
            }

            String brand = Text.clean(row.get("brand"));
            if (brand.length() > MAX_BRAND_LENGTH) errors.add(where + ": brand must be at most " + MAX_BRAND_LENGTH + " characters");

            String url = null;
            ProcessedImage image = null;
            String cell = Text.clean(row.get("image"));
            if (!cell.isEmpty()) {
                if (cell.toLowerCase(Locale.ROOT).startsWith("https://")) {
                    String problem = urlProblem(cell);
                    if (problem != null) errors.add(where + ": image " + problem);
                    else url = cell;
                } else if (cell.contains("://") || cell.toLowerCase(Locale.ROOT).startsWith("data:") || cell.toLowerCase(Locale.ROOT).startsWith("javascript:")) {
                    errors.add(where + ": image must be an uploaded file name or an https:// address");
                } else {
                    String key = ImageUploads.keyOf(cell);
                    Object found = imageCache.computeIfAbsent(key, k -> {
                        byte[] bytes = uploads.get(k);
                        if (bytes == null) return "was not uploaded";
                        try {
                            return pictures.process(bytes);
                        } catch (ImageException e) {
                            return e.getMessage();
                        }
                    });
                    if (found instanceof ProcessedImage p) image = p;
                    else errors.add(where + ": image '" + cell + "' " + found);
                }
            }
            if (errors.count == before) cards.add(new ImportedCard(name, url, image, stats, brand.isEmpty() ? null : brand));
        }

        List<StatSpec> settled = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            StatSpec s = specs.get(i);
            settled.add(s.valueType() != null ? s : new StatSpec(s.column(), s.key(), s.label(), s.unit(), s.higherWins(),
                    whole[i] ? com.cards.common.ValueType.INT : com.cards.common.ValueType.DECIMAL, s.displayFormat(), s.min(), s.max(), s.maxDecimals()));
        }
        return new Result(settled, errors.count == 0 ? cards : List.of(), errors.shown, errors.count);
    }

    private static BigDecimal number(String text, StatSpec spec, String where, Errors errors) {
        String t = text == null ? "" : text.trim();
        String range = spec.min().stripTrailingZeros().toPlainString() + " to " + spec.max().stripTrailingZeros().toPlainString();
        boolean integer = spec.valueType() == com.cards.common.ValueType.INT || spec.displayFormat() == com.cards.common.DisplayFormat.FEET_INCHES;
        if (!NUMBER.matcher(t).matches()) {
            errors.add(where + ": " + spec.column() + " must be a number from " + range + (integer ? " (a whole number)" : "") + (spec.displayFormat() == com.cards.common.DisplayFormat.FEET_INCHES ? ", as total inches" : ""));
            return null;
        }
        BigDecimal value = new BigDecimal(t);
        int scale = value.stripTrailingZeros().scale();
        if (value.compareTo(spec.min()) < 0 || value.compareTo(spec.max()) > 0 || scale > spec.maxDecimals() || (integer && scale > 0)) {
            errors.add(where + ": " + spec.column() + " must be a number from " + range + (integer ? " (a whole number)" : " with at most " + spec.maxDecimals() + " decimals")
                    + (spec.displayFormat() == com.cards.common.DisplayFormat.FEET_INCHES ? ", as total inches" : ""));
            return null;
        }
        value = value.stripTrailingZeros();
        return value.scale() < 0 ? value.setScale(0) : value;
    }

    /** An https address is only stored, never fetched, but it must still look like a plain web address. */
    static String urlProblem(String url) {
        if (url.length() > MAX_URL_LENGTH) return "address is longer than " + MAX_URL_LENGTH + " characters";
        if (url.chars().anyMatch(c -> c <= 32 || c == '"' || c == '<' || c == '>' || c == '\\' || c == '\'' || c == '`')) return "address contains characters that are not allowed";
        try {
            URI uri = new URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null) return "must be a plain https:// address";
        } catch (java.net.URISyntaxException e) {
            return "is not a valid address";
        }
        return null;
    }
}
