package com.cards.service.service;

import com.cards.common.BadRequestException;
import com.cards.common.ConflictException;
import com.cards.common.Deck;
import com.cards.common.DeckRequest;
import com.cards.common.NotFoundException;
import com.cards.common.Player;
import com.cards.common.Visibility;
import com.cards.service.db.CardRepository;
import com.cards.service.db.DeckRepository;
import com.cards.service.db.DeckRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A player's own decks: list, rename, delete, copy. (Creating and replacing go through {@link DeckImportService}, promotion through {@link DeckRequestService}.) */
@Service
public class DeckLibraryService {

    private final DeckRepository decks;
    private final CardRepository cards;
    private final DeckRequestRepository requests;
    private final DeckImportService imports;
    private final int maxDecksPerUser;

    public DeckLibraryService(DeckRepository decks, CardRepository cards, DeckRequestRepository requests, DeckImportService imports,
                              @Value("${app.decks.max-per-user:5}") int maxDecksPerUser) {
        this.decks = decks;
        this.cards = cards;
        this.requests = requests;
        this.imports = imports;
        this.maxDecksPerUser = maxDecksPerUser;
    }

    /** A deck of mine and the latest request to make it global (null if none). */
    public record MyDeck(Deck deck, DeckRequest promotion) {
    }

    @Transactional(readOnly = true)
    public List<MyDeck> mine(Player me) {
        Map<UUID, DeckRequest> promotions = new HashMap<>();
        for (DeckRequest r : requests.latestPromotionsOf(me.id())) promotions.put(r.resultingDeckId(), r);
        List<MyDeck> result = new ArrayList<>();
        for (Deck d : decks.findOwnedBy(me.id())) result.add(new MyDeck(d, promotions.get(d.id())));
        return result;
    }

    /** A deck the player may change: theirs, still PRIVATE, and with no promotion request open. */
    @Transactional(readOnly = true)
    public Deck editable(Player me, UUID deckId) {
        Deck deck = decks.findById(deckId).orElseThrow(() -> new NotFoundException("Deck not found"));
        DeckImportService.requireOwner(deck, me);
        if (deck.visibility() == Visibility.PUBLIC) throw new ConflictException("A public deck cannot be edited; copy it to a private deck and edit the copy");
        if (requests.hasOpenPromotion(deckId)) throw new ConflictException("Withdraw the request to make this deck global first");
        return deck;
    }

    @Transactional
    public Deck rename(Player me, UUID deckId, String name, String description) {
        Deck deck = editable(me, deckId);
        decks.rename(deckId, name == null ? deck.name() : DeckImportService.cleanName(name), description == null ? deck.description() : DeckImportService.cleanDescription(description));
        return decks.findById(deckId).orElseThrow();
    }

    /** Deletes the deck. A deck that was ever played in a game is kept (the game history refers to it). */
    @Transactional
    public void delete(Player me, UUID deckId) {
        Deck deck = decks.findById(deckId).orElseThrow(() -> new NotFoundException("Deck not found"));
        DeckImportService.requireOwner(deck, me);
        if (decks.isUsedByGames(deckId)) throw new ConflictException("This deck has been used in games and cannot be deleted");
        imports.deleteDeck(deckId);
    }

    /** A private copy of one of my decks (any visibility), with the same cards and images. */
    @Transactional
    public Deck copy(Player me, UUID deckId, String name) {
        Deck deck = decks.findById(deckId).orElseThrow(() -> new NotFoundException("Deck not found"));
        DeckImportService.requireOwner(deck, me);
        if (!deck.playable()) throw new ConflictException("The deck is not ready yet");
        if (decks.countOwnedBy(me.id()) >= maxDecksPerUser) throw new ConflictException("You can have at most " + maxDecksPerUser + " decks; delete one first");
        String copyName = name == null || name.isBlank() ? truncate(deck.name() + " (copy)") : DeckImportService.cleanName(name);
        if (copyName.length() < DeckImportService.MIN_NAME) throw new BadRequestException("name is too short");
        UUID id = decks.insertDeck(me.id(), copyName, deck.description(), Visibility.PRIVATE, true);
        decks.copyStats(deckId, id);
        cards.copyCards(deckId, id);
        return decks.findById(id).orElseThrow();
    }

    private static String truncate(String s) {
        return s.length() <= DeckImportService.MAX_NAME ? s : s.substring(0, DeckImportService.MAX_NAME - 7).trim() + " (copy)";
    }
}
