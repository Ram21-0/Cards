package com.cards.service.service;

import com.cards.common.ConflictException;
import com.cards.common.Deck;
import com.cards.common.DeckRequest;
import com.cards.common.DeckRequestStatus;
import com.cards.common.DeckRequestType;
import com.cards.common.ForbiddenException;
import com.cards.common.NotFoundException;
import com.cards.common.Player;
import com.cards.common.StatDefinition;
import com.cards.common.Visibility;
import com.cards.service.db.CardRepository;
import com.cards.service.db.DeckRepository;
import com.cards.service.db.DeckRequestRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeckLibraryServiceTest {

    private final DeckRepository decks = mock(DeckRepository.class);
    private final CardRepository cards = mock(CardRepository.class);
    private final DeckRequestRepository requests = mock(DeckRequestRepository.class);
    private final DeckImportService imports = mock(DeckImportService.class);
    private final DeckLibraryService service = new DeckLibraryService(decks, cards, requests, imports, 5);
    private final Player ann = Player.builder().id(UUID.randomUUID()).username("ann").build();
    private final Player eve = Player.builder().id(UUID.randomUUID()).username("eve").build();

    private Deck deck(Visibility visibility, int cards) {
        return Deck.builder().id(UUID.randomUUID()).ownerId(ann.id()).name("My deck").description("d").visibility(visibility).cardCount(cards)
                .stats(List.of(StatDefinition.builder().key("rank").build())).build();
    }

    private Deck stored(Deck d) {
        when(decks.findById(d.id())).thenReturn(Optional.of(d));
        return d;
    }

    @Test
    void myDecksComeWithTheirLatestPromotionRequest() {
        Deck a = deck(Visibility.PRIVATE, 10), b = deck(Visibility.PRIVATE, 10);
        DeckRequest promo = DeckRequest.builder().id(UUID.randomUUID()).type(DeckRequestType.PROMOTE).resultingDeckId(a.id()).status(DeckRequestStatus.REJECTED).adminNote("no").build();
        when(decks.findOwnedBy(ann.id())).thenReturn(List.of(a, b));
        when(requests.latestPromotionsOf(ann.id())).thenReturn(List.of(promo));

        List<DeckLibraryService.MyDeck> mine = service.mine(ann);

        assertEquals(promo, mine.get(0).promotion());
        assertNull(mine.get(1).promotion());
    }

    @Test
    void aDeckCanBeEditedWhenItIsPrivateAndNotUnderReview() {
        Deck d = stored(deck(Visibility.PRIVATE, 10));
        assertEquals(d, service.editable(ann, d.id()));
        assertThrows(NotFoundException.class, () -> service.editable(eve, d.id()));

        when(requests.hasOpenPromotion(d.id())).thenReturn(true);
        assertEquals("Withdraw the request to make this deck global first", assertThrows(ConflictException.class, () -> service.editable(ann, d.id())).getMessage());

        Deck pub = stored(deck(Visibility.PUBLIC, 10));
        assertEquals("A public deck cannot be edited; copy it to a private deck and edit the copy", assertThrows(ConflictException.class, () -> service.editable(ann, pub.id())).getMessage());
        assertThrows(ForbiddenException.class, () -> service.editable(eve, pub.id()));
    }

    @Test
    void renamingValidatesAndKeepsWhatIsNotGiven() {
        Deck d = stored(deck(Visibility.PRIVATE, 10));
        service.rename(ann, d.id(), "  New   name ", null);
        verify(decks).rename(d.id(), "New name", "d");
        assertThrows(com.cards.common.BadRequestException.class, () -> service.rename(ann, d.id(), "x", null));
    }

    @Test
    void aDeckThatWasPlayedCannotBeDeletedOthersCannotDeleteAndAnUnusedOneGoes() {
        Deck d = stored(deck(Visibility.PRIVATE, 10));
        when(decks.isUsedByGames(d.id())).thenReturn(true);
        assertEquals("This deck has been used in games and cannot be deleted", assertThrows(ConflictException.class, () -> service.delete(ann, d.id())).getMessage());
        assertThrows(NotFoundException.class, () -> service.delete(eve, d.id()));
        verify(imports, never()).deleteDeck(any());

        when(decks.isUsedByGames(d.id())).thenReturn(false);
        service.delete(ann, d.id());
        verify(imports).deleteDeck(d.id());
    }

    @Test
    void copyingMakesAPrivateDeckWithTheSameStatsCardsAndImages() {
        Deck pub = stored(deck(Visibility.PUBLIC, 10));
        UUID copyId = UUID.randomUUID();
        when(decks.insertDeck(ann.id(), "My deck (copy)", "d", Visibility.PRIVATE, true)).thenReturn(copyId);
        when(decks.findById(copyId)).thenReturn(Optional.of(deck(Visibility.PRIVATE, 10)));

        service.copy(ann, pub.id(), null);

        verify(decks).copyStats(pub.id(), copyId);
        verify(cards).copyCards(pub.id(), copyId);
    }

    @Test
    void copyIsOwnerOnlyAndRespectsTheDeckLimit() {
        Deck d = stored(deck(Visibility.PRIVATE, 10));
        assertThrows(NotFoundException.class, () -> service.copy(eve, d.id(), null));
        when(decks.countOwnedBy(ann.id())).thenReturn(5);
        assertEquals("You can have at most 5 decks; delete one first", assertThrows(ConflictException.class, () -> service.copy(ann, d.id(), null)).getMessage());
        verify(decks, never()).insertDeck(any(), any(), any(), any(), eq(true));
    }
}
