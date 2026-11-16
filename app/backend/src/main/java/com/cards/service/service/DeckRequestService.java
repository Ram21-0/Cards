package com.cards.service.service;

import com.cards.common.BadRequestException;
import com.cards.common.ConflictException;
import com.cards.common.Deck;
import com.cards.common.DeckRequest;
import com.cards.common.DeckRequestStatus;
import com.cards.common.NotFoundException;
import com.cards.common.Player;
import com.cards.common.ServiceUnavailableException;
import com.cards.common.TooManyRequestsException;
import com.cards.common.Visibility;
import com.cards.deckimport.DeckCardImport;
import com.cards.deckimport.StatImportParser;
import com.cards.deckimport.StatImportValidator;
import com.cards.service.db.CardRepository;
import com.cards.service.db.DeckRepository;
import com.cards.service.db.DeckRequestRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Deck requests: what users may submit, and what the admin does with them. */
@Service
public class DeckRequestService {

    public static final int MAX_PENDING_PER_USER = 3;
    public static final int MAX_PER_DAY = 5;
    public static final int MIN_NAMES = 3;
    public static final int MAX_NAMES = 60;
    public static final int MAX_NAME_LENGTH = 60;
    public static final int MIN_CATEGORY_LENGTH = 3;
    public static final int MAX_CATEGORY_LENGTH = 60;
    public static final int MAX_DESCRIPTION_LENGTH = 500;
    public static final int MAX_NOTE_LENGTH = 500;

    private final DeckRequestRepository requests;
    private final DeckRepository decks;
    private final CardRepository cards;
    private final DeckRequestNotifier notifier;
    private final Clock clock;
    private final String templateDeckName;

    public DeckRequestService(DeckRequestRepository requests, DeckRepository decks, CardRepository cards, DeckRequestNotifier notifier, Clock clock,
                              @Value("${app.deck-requests.template-deck:Wrestling}") String templateDeckName) {
        this.requests = requests;
        this.decks = decks;
        this.cards = cards;
        this.notifier = notifier;
        this.clock = clock;
        this.templateDeckName = templateDeckName;
    }

    // ------------------------------------------------------------------------------------------ users

    /** A signed-in player with a username submits a request. 409 at 3 pending, 429 at 5 in the last 24 h. The admin is mailed after the commit. */
    @Transactional
    public DeckRequest create(Player me, String category, String description, List<String> names) {
        if (me.username() == null) throw new BadRequestException("Choose a username first");
        String cleanCategory = cleanCategory(category);
        String cleanDescription = cleanDescription(description);
        List<String> cleanNames = cleanNames(names);

        requests.lockRequester(me.id());                                         // two simultaneous requests cannot both slip under a limit
        if (requests.countPending(me.id()) >= MAX_PENDING_PER_USER) {
            throw new ConflictException("You already have " + MAX_PENDING_PER_USER + " pending requests; wait for a decision");
        }
        if (requests.countSince(me.id(), clock.instant().minus(Duration.ofDays(1))) >= MAX_PER_DAY) {
            throw new TooManyRequestsException("You can send " + MAX_PER_DAY + " requests per day; try again tomorrow");
        }
        UUID id = requests.insert(me.id(), cleanCategory, cleanDescription, cleanNames, clock.instant());
        DeckRequest created = requests.findById(id).orElseThrow();
        afterCommit(() -> notifier.created(created));
        return created;
    }

    @Transactional(readOnly = true)
    public List<DeckRequest> mine(Player me) {
        return requests.findByRequester(me.id());
    }

    /** One of my own requests; somebody else's looks like it does not exist. */
    @Transactional(readOnly = true)
    public DeckRequest mine(Player me, UUID id) {
        return requests.findById(id).filter(r -> me.id().equals(r.requesterId()))
                .orElseThrow(() -> new NotFoundException("Request not found"));
    }

    // ------------------------------------------------------------------------------------------ admin

    /** Pending first. {@code status}: a status name, or null / "ALL" for every one. */
    @Transactional(readOnly = true)
    public List<DeckRequest> list(String status) {
        return requests.findAll(parseStatus(status));
    }

    @Transactional(readOnly = true)
    public DeckRequest get(UUID id) {
        return requests.findById(id).orElseThrow(() -> new NotFoundException("Request not found"));
    }

    /** The admin corrects a pending request: any of category, description, names (null = unchanged). Same cleaning and limits as a submission. */
    @Transactional
    public DeckRequest edit(UUID id, String category, String description, List<String> names) {
        DeckRequest r = requests.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Request not found"));
        requirePending(r);
        requests.updateContent(id,
                category == null ? r.category() : cleanCategory(category),
                description == null ? r.description() : cleanDescription(description),
                names == null ? r.names() : cleanNames(names));
        return requests.findById(id).orElseThrow();
    }

    /**
     * Approve as a PRIVATE (owner only) or PUBLIC (global) deck. Creates the deck record NOT live: it has no stats yet, so it is not
     * listed or playable until it is published (stage 2).
     */
    @Transactional
    public DeckRequest approve(UUID id, Player admin, String visibility, String note) {
        Visibility chosen = parseVisibility(visibility);
        String cleanNote = cleanNote(note, false);
        DeckRequest r = requests.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Request not found"));
        requirePending(r);
        UUID deckId = decks.insertNotLive(r.requesterId(), r.category(), r.description());
        DeckRequestStatus status = chosen == Visibility.PUBLIC ? DeckRequestStatus.APPROVED_GLOBAL : DeckRequestStatus.APPROVED_PRIVATE;
        if (!requests.decide(id, status, cleanNote, admin.id(), deckId, clock.instant())) throw new ConflictException("The request was decided meanwhile");
        return requests.findById(id).orElseThrow();
    }

    @Transactional
    public DeckRequest reject(UUID id, Player admin, String note) {
        String cleanNote = cleanNote(note, true);
        DeckRequest r = requests.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Request not found"));
        requirePending(r);
        if (!requests.decide(id, DeckRequestStatus.REJECTED, cleanNote, admin.id(), null, clock.instant())) throw new ConflictException("The request was decided meanwhile");
        return requests.findById(id).orElseThrow();
    }

    // ------------------------------------------------------------------------------------------ going live (stage 2a)

    /** The imported cards of an approved request's deck, for the admin to check before publishing. */
    @Transactional(readOnly = true)
    public ImportedDeck importedCards(UUID id) {
        DeckRequest r = get(id);
        if (r.resultingDeckId() == null) throw new ConflictException("Approve the request first");
        Deck deck = decks.findById(r.resultingDeckId()).orElseThrow(() -> new NotFoundException("Deck not found"));
        return new ImportedDeck(deck, cards.findByDeck(deck.id()));
    }

    public record ImportedDeck(Deck deck, List<com.cards.common.Card> cards) {
    }

    /** CSV text (header row: name, rank, fight, chest, biceps, height, weight, brand) or already typed cards: replaces all cards of the deck. */
    @Transactional
    public DeckRequest importCsv(UUID id, String csv) {
        return importStats(id, StatImportValidator.fromRows(StatImportParser.parseCsv(csv)));
    }

    @Transactional
    public DeckRequest importCards(UUID id, List<DeckCardImport> imported) {
        return importStats(id, StatImportValidator.fromCards(imported == null ? List.of() : imported));
    }

    private DeckRequest importStats(UUID id, List<DeckCardImport> validated) {
        DeckRequest r = requests.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Request not found"));
        requireApprovedAndNotLive(r);
        UUID deckId = r.resultingDeckId();
        if (decks.statCount(deckId) == 0) {                                      // the six Wrestling stats: same keys, units and strategies
            UUID template = decks.findTemplateId(templateDeckName).orElseThrow(() -> new ServiceUnavailableException("The template deck '" + templateDeckName + "' is missing"));
            decks.copyStats(template, deckId);
        }
        cards.replaceAll(deckId, validated);
        return requests.findById(id).orElseThrow();
    }

    /** Goes live: the deck gets the visibility the admin chose at approval and the request is marked published. Needs the imported cards. */
    @Transactional
    public DeckRequest publish(UUID id) {
        DeckRequest r = requests.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Request not found"));
        requireApprovedAndNotLive(r);
        if (r.cardCount() < StatImportValidator.MIN_CARDS || decks.statCount(r.resultingDeckId()) == 0) {
            throw new ConflictException("Import the card stats first (at least " + StatImportValidator.MIN_CARDS + " cards)");
        }
        decks.publish(r.resultingDeckId(), r.status() == DeckRequestStatus.APPROVED_GLOBAL ? Visibility.PUBLIC : Visibility.PRIVATE, clock.instant());
        requests.markPublished(id, clock.instant());
        return requests.findById(id).orElseThrow();
    }

    private static void requireApprovedAndNotLive(DeckRequest r) {
        if (!r.status().approved() || r.resultingDeckId() == null) throw new ConflictException("Approve the request first");
        if (r.publishedAt() != null) throw new ConflictException("The deck is already live");
    }

    // ------------------------------------------------------------------------------------------ cleaning and validation

    private static void requirePending(DeckRequest r) {
        if (r.status() != DeckRequestStatus.PENDING) throw new ConflictException("The request is already " + r.status().name());
    }

    private static DeckRequestStatus parseStatus(String status) {
        if (status == null || status.isBlank() || status.equalsIgnoreCase("ALL")) return null;
        for (DeckRequestStatus s : DeckRequestStatus.values()) {
            if (s.name().equalsIgnoreCase(status.trim())) return s;
        }
        throw new BadRequestException("status must be ALL, PENDING, REJECTED, APPROVED_PRIVATE or APPROVED_GLOBAL");
    }

    private static Visibility parseVisibility(String visibility) {
        if (visibility != null) {
            if (visibility.trim().equalsIgnoreCase("PRIVATE")) return Visibility.PRIVATE;
            if (visibility.trim().equalsIgnoreCase("PUBLIC")) return Visibility.PUBLIC;
        }
        throw new BadRequestException("visibility must be PRIVATE or PUBLIC");
    }

    /** Control characters out (a name is one line), runs of whitespace become one space, trimmed. */
    static String clean(String text) {
        if (text == null) return "";
        return text.replaceAll("[\\p{Cntrl}\\u2028\\u2029]+", " ").replaceAll("\\s+", " ").trim();
    }

    static String cleanCategory(String category) {
        String c = clean(category);
        if (c.length() < MIN_CATEGORY_LENGTH || c.length() > MAX_CATEGORY_LENGTH) {
            throw new BadRequestException("category must be " + MIN_CATEGORY_LENGTH + " to " + MAX_CATEGORY_LENGTH + " characters");
        }
        return c;
    }

    static String cleanDescription(String description) {
        String d = clean(description);
        if (d.length() > MAX_DESCRIPTION_LENGTH) throw new BadRequestException("description must be at most " + MAX_DESCRIPTION_LENGTH + " characters");
        return d.isEmpty() ? null : d;
    }

    static String cleanNote(String note, boolean required) {
        String n = clean(note);
        if (required && n.isEmpty()) throw new BadRequestException("A note is required");
        if (n.length() > MAX_NOTE_LENGTH) throw new BadRequestException("note must be at most " + MAX_NOTE_LENGTH + " characters");
        return n.isEmpty() ? null : n;
    }

    /** Trimmed, de-duplicated (ignoring case, first spelling wins), each at most 60 characters, 3 to 60 names. */
    static List<String> cleanNames(List<String> names) {
        if (names == null) throw new BadRequestException("names is required");
        if (names.size() > MAX_NAMES * 4) throw new BadRequestException("names may hold at most " + MAX_NAMES + " names");
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : names) {
            String name = clean(raw);
            if (name.isEmpty()) continue;
            if (name.length() > MAX_NAME_LENGTH) throw new BadRequestException("each name must be at most " + MAX_NAME_LENGTH + " characters");
            if (seen.add(name.toLowerCase(Locale.ROOT))) result.add(name);
        }
        if (result.size() < MIN_NAMES || result.size() > MAX_NAMES) {
            throw new BadRequestException("Suggest " + MIN_NAMES + " to " + MAX_NAMES + " different names");
        }
        return result;
    }

    private static void afterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }
}
