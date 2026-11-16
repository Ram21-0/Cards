package com.cards.common;

import com.cards.service.db.CardRepository;
import com.cards.service.db.DeckRepository;
import com.cards.service.service.DeckService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A deck that an approved request created is not live, and a deck without stats is never playable. */
class DeckPlayableTest {

    private static final StatDefinition STAT = StatDefinition.builder().key("rank").label("Rank").valueType(ValueType.INT)
            .comparisonStrategy(ComparisonStrategy.LOWER_WINS).displayFormat(DisplayFormat.PLAIN).displayOrder(1).build();

    private static Deck deck(Boolean live, Visibility visibility, List<StatDefinition> stats) {
        return Deck.builder().id(UUID.randomUUID()).name("D").visibility(visibility).live(live).stats(stats).build();
    }

    @Test
    void liveOrUnsetWithStatsIsPlayable() {
        assertTrue(deck(null, Visibility.PUBLIC, List.of(STAT)).playable());
        assertTrue(deck(true, Visibility.PUBLIC, List.of(STAT)).playable());
    }

    @Test
    void notLiveOrWithoutStatsIsNeverPlayable() {
        assertFalse(deck(false, Visibility.PUBLIC, List.of(STAT)).playable());
        assertFalse(deck(true, Visibility.PUBLIC, List.of()).playable());
        assertFalse(deck(false, Visibility.PUBLIC, List.of()).playable());
    }

    @Test
    void thePublicDeckLookupHidesNotLiveAndStatlessDecks() {
        DeckRepository repository = mock(DeckRepository.class);
        DeckService service = new DeckService(repository, mock(CardRepository.class));
        Deck ok = deck(true, Visibility.PUBLIC, List.of(STAT)), notLive = deck(false, Visibility.PUBLIC, List.of(STAT)), empty = deck(true, Visibility.UNLISTED, List.of());
        when(repository.findById(ok.id())).thenReturn(Optional.of(ok));
        when(repository.findById(notLive.id())).thenReturn(Optional.of(notLive));
        when(repository.findById(empty.id())).thenReturn(Optional.of(empty));

        assertEquals(ok, service.get(ok.id()));
        assertThrows(NotFoundException.class, () -> service.get(notLive.id()));
        assertThrows(NotFoundException.class, () -> service.get(empty.id()));
    }
}
