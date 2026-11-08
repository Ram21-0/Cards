package com.cards.service.service;

import com.cards.common.BadRequestException;
import com.cards.common.Deck;
import com.cards.common.Game;
import com.cards.common.GameStatus;
import com.cards.common.NotFoundException;
import com.cards.common.Player;
import com.cards.common.SeatInfo;
import com.cards.common.SeatKind;
import com.cards.common.Visibility;
import com.cards.service.db.DeckRepository;
import com.cards.service.db.GameRepository;
import com.cards.service.db.PlayerRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GameServiceTest {

    private static final UUID DECK_ID = UUID.randomUUID();
    private static final UUID HOST_ID = UUID.randomUUID();

    private final GameRepository games = mock(GameRepository.class);
    private final DeckService decks = mock(DeckService.class);
    private final DeckRepository deckRepository = mock(DeckRepository.class);
    private final PlayerRepository players = mock(PlayerRepository.class);
    private final GameService service = new GameService(games, decks, deckRepository, players);

    private static Deck deck(int cardCount) {
        return Deck.builder().id(DECK_ID).name("Wrestling").visibility(Visibility.PUBLIC).cardCount(cardCount).stats(List.of()).build();
    }

    private static Game game(String code) {
        return Game.builder().id(UUID.randomUUID()).joinCode(code).deckId(DECK_ID).maxPlayers(4).status(GameStatus.WAITING).build();
    }

    @Test
    void deckIdIsRequired() {
        assertThrows(BadRequestException.class, () -> service.create(null, 4, null));
        verifyNoInteractions(games, decks);
    }

    @Test
    void maxPlayersMustBeBetweenTwoAndEight() {
        for (Integer bad : new Integer[]{null, 0, 1, 9, 100}) {
            assertThrows(BadRequestException.class, () -> service.create(DECK_ID, bad, null), "maxPlayers " + bad);
        }
        verifyNoInteractions(games, decks);
        // the edges are fine
        when(decks.get(DECK_ID)).thenReturn(deck(54));
        when(games.insertIfCodeFree(anyString(), any(), any(), anyInt())).thenAnswer(i -> Optional.of(game(i.getArgument(0))));
        service.create(DECK_ID, 2, null);
        service.create(DECK_ID, 8, null);
    }

    @Test
    void deckNeedsAtLeastOneCardPerPlayer() {
        when(decks.get(DECK_ID)).thenReturn(deck(3));
        BadRequestException e = assertThrows(BadRequestException.class, () -> service.create(DECK_ID, 4, null));
        assertEquals("This deck has 3 cards; 4 players need at least 4", e.getMessage());
        verify(games, never()).insertIfCodeFree(anyString(), any(), any(), anyInt());
    }

    @Test
    void unknownOrPrivateDeckIsNotFound() {
        when(decks.get(DECK_ID)).thenThrow(new NotFoundException("Deck not found"));
        assertThrows(NotFoundException.class, () -> service.create(DECK_ID, 4, null));
    }

    @Test
    void unknownHostIsNotFound() {
        when(decks.get(DECK_ID)).thenReturn(deck(54));
        when(players.findById(HOST_ID)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.create(DECK_ID, 4, HOST_ID));
        verify(games, never()).insertIfCodeFree(anyString(), any(), any(), anyInt());
    }

    @Test
    void hostTakesSeatOne() {
        when(decks.get(DECK_ID)).thenReturn(deck(54));
        when(players.findById(HOST_ID)).thenReturn(Optional.of(Player.builder().id(HOST_ID).username("ann").createdAt(Instant.now()).build()));
        Game created = game("K7Q2XM");
        when(games.insertIfCodeFree(anyString(), eq(DECK_ID), eq(HOST_ID), eq(4))).thenReturn(Optional.of(created));
        List<SeatInfo> seats = List.of(new SeatInfo(1, SeatKind.HUMAN, "ann", null));
        when(games.findSeats(created.id())).thenReturn(seats);

        GameDetails d = service.create(DECK_ID, 4, HOST_ID);

        verify(games).addHumanSeat(created.id(), 1, HOST_ID);
        assertEquals(seats, d.seats());
        assertEquals("K7Q2XM", d.game().joinCode());
    }

    @Test
    void anonymousHostGetsNoSeat() {
        when(decks.get(DECK_ID)).thenReturn(deck(54));
        when(games.insertIfCodeFree(anyString(), any(), any(), anyInt())).thenAnswer(i -> Optional.of(game(i.getArgument(0))));
        service.create(DECK_ID, 4, null);
        verify(games, never()).addHumanSeat(any(), anyInt(), any());
    }

    @Test
    void retriesWithAnotherCodeOnCollision() {
        when(decks.get(DECK_ID)).thenReturn(deck(54));
        when(games.insertIfCodeFree(anyString(), any(), any(), anyInt()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty())
                .thenAnswer(i -> Optional.of(game(i.getArgument(0))));
        service.create(DECK_ID, 4, null);
        verify(games, times(3)).insertIfCodeFree(anyString(), any(), any(), anyInt());
    }

    @Test
    void givesUpWhenNoCodeIsFree() {
        when(decks.get(DECK_ID)).thenReturn(deck(54));
        when(games.insertIfCodeFree(anyString(), any(), any(), anyInt())).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> service.create(DECK_ID, 4, null));
    }

    @Test
    void getNormalizesTheCodeAndLoadsTheDeckWithoutTheVisibilityFilter() {
        Game g = game("K7Q2XM");
        when(games.findByJoinCode("K7Q2XM")).thenReturn(Optional.of(g));
        when(deckRepository.findById(DECK_ID)).thenReturn(Optional.of(deck(54)));
        when(games.findSeats(g.id())).thenReturn(List.of());

        GameDetails d = service.get("  k7q2xm ");

        assertEquals("K7Q2XM", d.game().joinCode());
        verify(deckRepository).findById(DECK_ID);
        verifyNoInteractions(decks);
    }

    @Test
    void getUnknownCodeIsNotFound() {
        when(games.findByJoinCode("NOPE12")).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.get("nope12"));
        assertThrows(NotFoundException.class, () -> service.get(null));
    }
}
