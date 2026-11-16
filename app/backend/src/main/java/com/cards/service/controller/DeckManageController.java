package com.cards.service.controller;

import com.cards.common.BadRequestException;
import com.cards.common.Deck;
import com.cards.common.Player;
import com.cards.deckimport.NamedBytes;
import com.cards.live.RateLimiter;
import com.cards.service.controller.dto.CopyDeckBody;
import com.cards.service.controller.dto.DeckRequestResponse;
import com.cards.service.controller.dto.DeckResponse;
import com.cards.service.controller.dto.MyDeckResponse;
import com.cards.service.controller.dto.RenameDeckBody;
import com.cards.service.service.DeckImportService;
import com.cards.service.service.DeckLibraryService;
import com.cards.service.service.DeckRequestService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A signed-in player's own decks: upload a CSV (+ images) for a PRIVATE deck, preview it first, list / rename / replace / copy / delete, and ask for a deck
 * to be made global. See docs/deck-requests/API.md.
 */
@RestController
@RequestMapping("/api/decks")
public class DeckManageController {

    static final long MAX_CSV_BYTES = 1_000_000;

    private final DeckImportService imports;
    private final DeckLibraryService library;
    private final DeckRequestService requests;
    private final RateLimiter rateLimiter;
    private final RateLimiter commitLimiter;
    private final RateLimiter previewLimiter;

    public DeckManageController(DeckImportService imports, DeckLibraryService library, DeckRequestService requests, RateLimiter rateLimiter, Clock clock) {
        this.imports = imports;
        this.library = library;
        this.requests = requests;
        this.rateLimiter = rateLimiter;
        this.commitLimiter = new RateLimiter(clock, 10, 3600);        // 10 saved uploads per hour per player
        this.previewLimiter = new RateLimiter(clock, 30, 3600);       // 30 previews per hour per player
    }

    @GetMapping("/mine")
    public List<MyDeckResponse> mine(@AuthenticationPrincipal Player me) {
        return library.mine(me).stream().map(MyDeckResponse::from).toList();
    }

    /** The Wrestling schema as an example: a CSV that passes validation and the mapping that goes with it. */
    @GetMapping("/import/template")
    public Map<String, Object> template() {
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("stats", List.of(
                stat("rank", "Rank", null, false, "PLAIN"), stat("fight", "Fight", null, true, "PLAIN"), stat("chest", "Chest", "in", true, "PLAIN"),
                stat("biceps", "Biceps", "in", true, "PLAIN"), stat("height", "Height", "in", true, "FEET_INCHES"), stat("weight", "Weight", "lbs", true, "WITH_UNIT")));
        StringBuilder csv = new StringBuilder("name,image,brand,rank,fight,chest,biceps,height,weight\n");
        for (int i = 1; i <= 8; i++) {
            csv.append("Example ").append(i).append(",example").append(i).append(".jpg,raw,").append(i).append(',').append(50 - i).append(',').append(40 - i)
                    .append(',').append(16 - i / 4.0).append(',').append(70 + i).append(',').append(200 + 5 * i).append('\n');
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("csv", csv.toString());
        body.put("mapping", mapping);
        body.put("notes", List.of("name is required; image (an uploaded file name or an https address) and brand are optional; every other mapped column is a stat",
                "send the CSV as the 'csv' part, the images as a ZIP or several files in 'images', this mapping as the 'mapping' part (JSON text), plus 'name' and 'description'",
                "height is total inches (feet x 12 + inches); 8 to 100 cards; at most 8 stats"));
        return body;
    }

    private static Map<String, Object> stat(String column, String label, String unit, boolean higherWins, String format) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("column", column);
        s.put("label", label);
        s.put("unit", unit);
        s.put("higherWins", higherWins);
        s.put("displayFormat", format);
        return s;
    }

    /**
     * {@code dryRun=true}: validates and answers 200 with every problem and the parsed cards, saving nothing. Otherwise creates the private deck (201), or answers 400
     * with the first problems.
     */
    @PostMapping("/import")
    public ResponseEntity<?> importDeck(@AuthenticationPrincipal Player me, @RequestParam(defaultValue = "false") boolean dryRun,
                                        @RequestParam("csv") MultipartFile csv, @RequestParam("mapping") String mapping,
                                        @RequestParam(value = "name", required = false) String name, @RequestParam(value = "description", required = false) String description,
                                        @RequestParam(value = "images", required = false) List<MultipartFile> images) {
        rateLimiter.check(me.id());
        (dryRun ? previewLimiter : commitLimiter).check(me.id());
        if (dryRun) return ResponseEntity.ok(imports.preview(text(csv), mapping, files(images)));
        Deck deck = imports.createPrivateDeck(me, name, description, text(csv), mapping, files(images));
        return ResponseEntity.status(HttpStatus.CREATED).body(DeckResponse.from(deck));
    }

    /** Replaces the cards, stats and (if given) name/description of one of my private decks with a new upload. */
    @PutMapping("/{id}/import")
    public DeckResponse replace(@AuthenticationPrincipal Player me, @PathVariable UUID id, @RequestParam("csv") MultipartFile csv, @RequestParam("mapping") String mapping,
                                @RequestParam(value = "name", required = false) String name, @RequestParam(value = "description", required = false) String description,
                                @RequestParam(value = "images", required = false) List<MultipartFile> images) {
        rateLimiter.check(me.id());
        commitLimiter.check(me.id());
        library.editable(me, id);
        return DeckResponse.from(imports.replaceDeck(me, id, name, description, text(csv), mapping, files(images)));
    }

    @PatchMapping("/{id}")
    public DeckResponse rename(@AuthenticationPrincipal Player me, @PathVariable UUID id, @RequestBody RenameDeckBody body) {
        rateLimiter.check(me.id());
        return DeckResponse.from(library.rename(me, id, body.name(), body.description()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Player me, @PathVariable UUID id) {
        rateLimiter.check(me.id());
        library.delete(me, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/copy")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public DeckResponse copy(@AuthenticationPrincipal Player me, @PathVariable UUID id, @RequestBody(required = false) CopyDeckBody body) {
        rateLimiter.check(me.id());
        return DeckResponse.from(library.copy(me, id, body == null ? null : body.name()));
    }

    /** The owner asks to make this private deck global; the admin is mailed. */
    @PostMapping("/{id}/request-global")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public DeckRequestResponse requestGlobal(@AuthenticationPrincipal Player me, @PathVariable UUID id) {
        rateLimiter.check(me.id());
        return DeckRequestResponse.from(requests.createPromotion(me, id));
    }

    // ------------------------------------------------------------------------------------------ reading the parts

    private static String text(MultipartFile csv) {
        if (csv.getSize() > MAX_CSV_BYTES) throw new BadRequestException("The CSV is larger than 1 MB");
        try {
            return new String(csv.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BadRequestException("The CSV could not be read");
        }
    }

    private static List<NamedBytes> files(List<MultipartFile> images) {
        List<NamedBytes> files = new ArrayList<>();
        if (images == null) return files;
        for (MultipartFile f : images) {
            if (f == null || f.isEmpty()) continue;
            try {
                files.add(new NamedBytes(f.getOriginalFilename(), f.getBytes()));
            } catch (IOException e) {
                throw new BadRequestException("An image could not be read");
            }
        }
        return files;
    }
}
