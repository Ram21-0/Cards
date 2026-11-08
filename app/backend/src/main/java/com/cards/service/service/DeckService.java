package com.cards.service.service;

import com.cards.common.Card;
import com.cards.common.Deck;
import com.cards.common.NotFoundException;
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

    /** PUBLIC and UNLISTED decks are readable by id. PRIVATE ones look "not found" until accounts exist (owner check goes here). */
    public Deck get(UUID id) {
        return decks.findById(id)
                .filter(d -> d.visibility() != Visibility.PRIVATE)
                .orElseThrow(() -> new NotFoundException("Deck " + id + " not found"));
    }

    /** Cards of a deck the caller is allowed to see (get the deck through {@link #get} first). */
    public List<Card> cardsOf(Deck deck) {
        return cards.findByDeck(deck.id());
    }
}
