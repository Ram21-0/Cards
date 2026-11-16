package com.cards.service.service;

import com.cards.common.Card;
import com.cards.common.Deck;
import com.cards.common.NotFoundException;
import com.cards.common.Player;
import com.cards.common.Visibility;
import com.cards.service.db.CardRepository;
import com.cards.service.db.DeckRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DeckService {

    private final DeckRepository decks;
    private final CardRepository cards;

    public List<Deck> listPublic() {
        return decks.findPublic();
    }

    /** Public decks, plus the signed-in player's own private ones (published decks of an approved request they made). */
    public List<Deck> listFor(Player viewer) {
        return viewer == null ? decks.findPublic() : decks.findVisibleTo(viewer.id());
    }

    /** PUBLIC and UNLISTED decks are readable by id. PRIVATE ones look "not found" until accounts exist (owner check goes here). */
    public Deck get(UUID id) {
        return decks.findById(id)
                .filter(d -> d.visibility() != Visibility.PRIVATE)
                .filter(Deck::playable)
                .orElseThrow(() -> new NotFoundException("Deck " + id + " not found"));
    }

    /**
     * Like {@link #get(UUID)}, but a PRIVATE deck is also readable by its owner and by a player who sits in a game that uses it
     * (the other players need its cards to play). A deck that is not live or has no stats is never readable.
     */
    public Deck get(UUID id, Player viewer) {
        return get(id, viewer, false);
    }

    /** An admin may read any deck that is playable (to review an upload before making it global). */
    public Deck get(UUID id, Player viewer, boolean admin) {
        return decks.findById(id)
                .filter(Deck::playable)
                .filter(d -> admin || d.visibility() != Visibility.PRIVATE
                        || (viewer != null && (viewer.id().equals(d.ownerId()) || decks.isSeatedWithDeck(viewer.id(), d.id()))))
                .orElseThrow(() -> new NotFoundException("Deck " + id + " not found"));
    }

    /** Cards of a deck the caller is allowed to see (get the deck through {@link #get} first). */
    public List<Card> cardsOf(Deck deck) {
        return cards.findByDeck(deck.id());
    }
}
