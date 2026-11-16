package com.cards.service.service;

import com.cards.common.BadRequestException;
import com.cards.common.ConflictException;
import com.cards.common.Deck;
import com.cards.common.ForbiddenException;
import com.cards.common.NotFoundException;
import com.cards.common.Player;
import com.cards.common.Visibility;
import com.cards.deckimport.ImageProcessor;
import com.cards.deckimport.ImageUploads;
import com.cards.deckimport.ImportValidator;
import com.cards.deckimport.ImportedCard;
import com.cards.deckimport.MappingParser;
import com.cards.deckimport.NamedBytes;
import com.cards.deckimport.ProcessedImage;
import com.cards.deckimport.StatImportParser;
import com.cards.deckimport.StatSpec;
import com.cards.deckimport.Text;
import com.cards.image.ImageStore;
import com.cards.service.db.CardRepository;
import com.cards.service.db.DeckRepository;
import com.cards.service.db.DeckRequestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * THE import pipeline: CSV text + a stat mapping + optional images, in; a validated deck, out. It is used twice: a user's own upload (a PRIVATE deck
 * they own, any mapping) and the admin's import that fills the deck of an approved category request (the stat definitions of the built-in
 * Wrestling deck). Everything is checked before anything is written, and a commit is one transaction.
 */
@Service
public class DeckImportService {

    public static final int MIN_NAME = 3, MAX_NAME = 40, MAX_DESCRIPTION = 300;

    private final DeckRepository decks;
    private final CardRepository cards;
    private final DeckRequestRepository requests;
    private final ImageStore images;
    private final ImageProcessor processor;
    private final ObjectMapper json;
    private final int maxDecksPerUser;

    public DeckImportService(DeckRepository decks, CardRepository cards, DeckRequestRepository requests, ImageStore images, ImageProcessor processor,
                             ObjectMapper json, @Value("${app.decks.max-per-user:5}") int maxDecksPerUser) {
        this.decks = decks;
        this.cards = cards;
        this.requests = requests;
        this.images = images;
        this.processor = processor;
        this.json = json;
        this.maxDecksPerUser = maxDecksPerUser;
    }

    // ------------------------------------------------------------------------------------------ preview (nothing is saved)

    public record Preview(boolean valid, List<String> errors, int errorCount, List<PreviewStat> stats, List<PreviewCard> cards) {
    }

    public record PreviewStat(String key, String column, String label, String unit, boolean higherWins, String valueType, String displayFormat) {
    }

    public record PreviewCard(String name, String image, String brand, Map<String, BigDecimal> stats) {
    }

    /** Validates an upload and returns every problem, or the parsed cards. A bad mapping or an unreadable CSV is a 400; problems in the data are not. */
    public Preview preview(String csv, String mapping, List<NamedBytes> files) {
        ImportValidator.Result result = validate(csv, mapping, files);
        List<PreviewCard> cardsShown = new ArrayList<>();
        for (ImportedCard c : result.cards()) {
            String image = c.imageUrl() != null ? c.imageUrl() : c.image() != null ? "(uploaded image " + c.image().width() + "x" + c.image().height() + ")" : null;
            cardsShown.add(new PreviewCard(c.name(), image, c.brand(), c.stats()));
        }
        List<PreviewStat> stats = result.specs().stream().map(s -> new PreviewStat(s.key(), s.column(), s.label(), s.unit(), s.higherWins(),
                s.valueType().name(), s.displayFormat().name())).toList();
        return new Preview(result.ok(), result.errors(), result.errorCount(), stats, cardsShown);
    }

    // ------------------------------------------------------------------------------------------ a user's own decks

    /** Creates a PRIVATE, live deck owned by {@code owner}. */
    @Transactional
    public Deck createPrivateDeck(Player owner, String name, String description, String csv, String mapping, List<NamedBytes> files) {
        String cleanName = cleanName(name), cleanDescription = cleanDescription(description);
        if (decks.countOwnedBy(owner.id()) >= maxDecksPerUser) {
            throw new ConflictException("You can have at most " + maxDecksPerUser + " decks; delete one first");
        }
        ImportValidator.Result result = requireValid(validate(csv, mapping, files));
        UUID deckId = decks.insertDeck(owner.id(), cleanName, cleanDescription, Visibility.PRIVATE, true);
        decks.insertStats(deckId, result.specs());
        saveCards(deckId, owner.id(), result);
        return decks.findById(deckId).orElseThrow();
    }

    /** Replaces the stats and cards of one of the owner's private decks (the caller has checked the deck may be edited). */
    @Transactional
    public Deck replaceDeck(Player owner, UUID deckId, String name, String description, String csv, String mapping, List<NamedBytes> files) {
        Deck deck = decks.findById(deckId).orElseThrow(() -> new NotFoundException("Deck not found"));
        ImportValidator.Result result = requireValid(validate(csv, mapping, files));
        List<String> oldImages = cards.imageUrlsOf(deckId);
        decks.rename(deckId, name == null || name.isBlank() ? deck.name() : cleanName(name), description == null ? deck.description() : cleanDescription(description));
        decks.deleteStats(deckId);
        decks.insertStats(deckId, result.specs());
        saveCards(deckId, owner.id(), result);
        cleanUp(oldImages);
        return decks.findById(deckId).orElseThrow();
    }

    // ------------------------------------------------------------------------------------------ an admin fills a requested deck

    /** The same pipeline for the admin: the stat definitions are the template deck's, tight ranges, up to 500 cards, images by https URL only. */
    @Transactional
    public void fillRequestedDeck(UUID deckId, UUID ownerId, StatImportParser.Table table, List<StatSpec> specs) {
        ImportValidator.Result result = requireValid(ImportValidator.validate(table, specs, Map.of(), processor, ImportValidator.Options.ADMIN));
        if (decks.statCount(deckId) == 0) decks.insertStats(deckId, result.specs());
        List<String> oldImages = cards.imageUrlsOf(deckId);
        saveCards(deckId, ownerId, result);
        cleanUp(oldImages);
    }

    // ------------------------------------------------------------------------------------------ deleting

    /** Deletes the deck and the images only it used. */
    @Transactional
    public void deleteDeck(UUID deckId) {
        List<String> oldImages = cards.imageUrlsOf(deckId);
        requests.deleteOpenPromotions(deckId);
        decks.deleteDeck(deckId);
        cleanUp(oldImages);
    }

    // ------------------------------------------------------------------------------------------ internals

    private ImportValidator.Result validate(String csv, String mapping, List<NamedBytes> files) {
        List<StatSpec> specs = MappingParser.parse(json, mapping);
        StatImportParser.Table table = StatImportParser.parse(csv);
        return ImportValidator.validate(table, specs, ImageUploads.collect(files), processor, ImportValidator.Options.USER);
    }

    private static ImportValidator.Result requireValid(ImportValidator.Result result) {
        if (!result.ok()) {
            List<String> shown = result.errors().subList(0, Math.min(5, result.errors().size()));
            String message = String.join("; ", shown);
            if (result.errorCount() > shown.size()) message += "; and " + (result.errorCount() - shown.size()) + " more problems (use the preview to see them all)";
            throw new BadRequestException(message);
        }
        return result;
    }

    /** Stores each distinct uploaded image once, then replaces the deck's cards. */
    private void saveCards(UUID deckId, UUID ownerId, ImportValidator.Result result) {
        Map<ProcessedImage, String> stored = new IdentityHashMap<>();
        List<ImportedCard> ready = new ArrayList<>();
        for (ImportedCard c : result.cards()) {
            if (c.image() == null) {
                ready.add(c);
                continue;
            }
            String url = stored.computeIfAbsent(c.image(), p -> ImageStore.urlOf(images.store(ownerId, p.bytes(), p.contentType(), p.width(), p.height())));
            ready.add(c.withImageUrl(url));
        }
        cards.replaceAll(deckId, ready);
    }

    private void cleanUp(List<String> oldImageUrls) {
        List<UUID> ids = oldImageUrls.stream().map(ImageStore::idOf).filter(java.util.Objects::nonNull).distinct().toList();
        if (!ids.isEmpty()) images.deleteUnused(ids);
    }

    static String cleanName(String name) {
        String n = Text.clean(name);
        if (n.length() < MIN_NAME || n.length() > MAX_NAME) throw new BadRequestException("name must be " + MIN_NAME + " to " + MAX_NAME + " characters");
        return n;
    }

    static String cleanDescription(String description) {
        String d = Text.clean(description);
        if (d.length() > MAX_DESCRIPTION) throw new BadRequestException("description must be at most " + MAX_DESCRIPTION + " characters");
        return d.isEmpty() ? null : d;
    }

    /** Ownership checks shared by the edit endpoints. */
    public static void requireOwner(Deck deck, Player player) {
        if (deck.ownerId() == null || !deck.ownerId().equals(player.id())) {
            if (deck.visibility() == Visibility.PRIVATE) throw new NotFoundException("Deck not found");
            throw new ForbiddenException("Only the owner can do that");
        }
    }
}
