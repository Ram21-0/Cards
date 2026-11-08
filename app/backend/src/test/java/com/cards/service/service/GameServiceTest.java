package com.cards.service.service;

import com.cards.common.BadRequestException;
import com.cards.common.ConflictException;
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
    private static final Player ANN = Player.builder().id(UUID.randomUUID()).username("ann").createdAt(Instant.now()).build();
    private static final Player BOB = Player.builder().id(UUID.randomUUID()).username("bob").createdAt(Instant.now()).build();
    private static final Player NO_NAME = Player.builder().id(UUID.randomUUID()).displayName("New Person").createdAt(Instant.now()).build();

    private final GameRepository games = mock(GameRepository.class);
    private final DeckService decks = mock(DeckService.class);
    private final DeckRepository deckRepository = mock(DeckRepository.class);
    private final GameService service = new GameService(games, decks, deckRepository);

    private static Deck deck(int cardCount) {
        return Deck.builder().id(DECK_ID).name("Wrestling").visibility(Visibility.PUBLIC).cardCount(cardCount).stats(List.of()).build();
    }

    private static Game game(String code, int maxPlayers, GameStatus status) {
        return Game.builder().id(UUID.randomUUID()).joinCode(code).deckId(DECK_ID).maxPlayers(maxPlayers).status(status).build();
    }

    private static SeatInfo seat(int n, String username) {
        return new SeatInfo(n, SeatKind.HUMAN, username, null);
    }

    // ------------------------------------------------------------ create

    @Test
    void deckIdIsRequired() {
        assertThrows(BadRequestException.class, () -> service.create(null, 4, ANN));
        verifyNoInteractions(games, decks);
    }

    @Test
    void maxPlayersMustBeBetweenTwoAndEight() {
        for (Integer bad : new Integer[]{null, 0, 1, 9, 100}) {
            assertThrows(BadRequestException.class, () -> service.create(DECK_ID, bad, ANN), "maxPlayers " + bad);
        }
        verifyNoInteractions(games, decks);
        // the edges are fine
        when(decks.get(DECK_ID)).thenReturn(deck(54));
        when(games.insertIfCodeFree(anyString(), any(), any(), anyInt())).thenAnswer(i -> Optional.of(game(i.getArgument(0), 4, GameStatus.WAITING)));
        service.create(DECK_ID, 2, ANN);
        service.create(DECK_ID, 8, ANN);
    }

    @Test
    void deckNeedsAtLeastOneCardPerPlayer() {
        when(decks.get(DECK_ID)).thenReturn(deck(3));
        BadRequestException e = assertThrows(BadRequestException.class, () -> service.create(DECK_ID, 4, ANN));
        assertEquals("This deck has 3 cards; 4 players need at least 4", e.getMessage());
        verify(games, never()).insertIfCodeFree(anyString(), any(), any(), anyInt());
    }

    @Test
    void unknownOrPrivateDeckIsNotFound() {
        when(decks.get(DECK_ID)).thenThrow(new NotFoundException("Deck not found"));
        assertThrows(NotFoundException.class, () -> service.create(DECK_ID, 4, ANN));
    }

    @Test
    void thePlayerMustHaveChosenAUsername() {
        BadRequestException e = assertThrows(BadRequestException.class, () -> service.create(DECK_ID, 4, NO_NAME));
        assertEquals("Choose a username first", e.getMessage());
        verifyNoInteractions(games, decks);
    }

    @Test
    void theSignedInHostIsTheGameHostAndTakesSeatOne() {
        when(decks.get(DECK_ID)).thenReturn(deck(54));
        Game created = game("K7Q2XM", 4, GameStatus.WAITING);
        when(games.insertIfCodeFree(anyString(), eq(DECK_ID), eq(ANN.id()), eq(4))).thenReturn(Optional.of(created));
        List<SeatInfo> seats = List.of(seat(1, "ann"));
        when(games.findSeats(created.id())).thenReturn(seats);

        GameDetails d = service.create(DECK_ID, 4, ANN);

        verify(games).addHumanSeat(created.id(), 1, ANN.id());
        assertEquals(seats, d.seats());
        assertEquals("K7Q2XM", d.game().joinCode());
    }

    @Test
    void retriesWithAnotherCodeOnCollision() {
        when(decks.get(DECK_ID)).thenReturn(deck(54));
        when(games.insertIfCodeFree(anyString(), any(), any(), anyInt()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty())
                .thenAnswer(i -> Optional.of(game(i.getArgument(0), 4, GameStatus.WAITING)));
        service.create(DECK_ID, 4, ANN);
        verify(games, times(3)).insertIfCodeFree(anyString(), any(), any(), anyInt());
    }

    @Test
    void givesUpWhenNoCodeIsFree() {
        when(decks.get(DECK_ID)).thenReturn(deck(54));
        when(games.insertIfCodeFree(anyString(), any(), any(), anyInt())).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> service.create(DECK_ID, 4, ANN));
    }

    // ------------------------------------------------------------ join

    private Game waitingGame(int maxPlayers, List<SeatInfo> seats) {
        Game g = game("K7Q2XM", maxPlayers, GameStatus.WAITING);
        when(games.findByJoinCodeForUpdate("K7Q2XM")).thenReturn(Optional.of(g));
        when(deckRepository.findById(DECK_ID)).thenReturn(Optional.of(deck(54)));
        when(games.findSeats(g.id())).thenReturn(seats);
        when(games.isSeated(eq(g.id()), any())).thenReturn(false);
        return g;
    }

    @Test
    void joiningTakesTheLowestFreeSeat() {
        Game g = waitingGame(4, List.of(seat(1, "ann"), seat(3, "cy")));
        service.join("K7Q2XM", BOB);
        verify(games).addHumanSeat(g.id(), 2, BOB.id());
    }

    @Test
    void joiningAnEmptyGameTakesSeatOne() {
        Game g = waitingGame(3, List.of());
        service.join("K7Q2XM", BOB);
        verify(games).addHumanSeat(g.id(), 1, BOB.id());
    }

    @Test
    void theLastSeatCanBeTaken() {
        Game g = waitingGame(3, List.of(seat(1, "ann"), seat(2, "cy")));
        service.join("K7Q2XM", BOB);
        verify(games).addHumanSeat(g.id(), 3, BOB.id());
    }

    @Test
    void aFullGameIsAConflict() {
        waitingGame(2, List.of(seat(1, "ann"), seat(2, "cy")));
        ConflictException e = assertThrows(ConflictException.class, () -> service.join("K7Q2XM", BOB));
        assertEquals("Game is full", e.getMessage());
        verify(games, never()).addHumanSeat(any(), anyInt(), any());
    }

    @Test
    void aGameThatAlreadyStartedCannotBeJoined() {
        Game started = game("K7Q2XM", 4, GameStatus.IN_PROGRESS);
        when(games.findByJoinCodeForUpdate("K7Q2XM")).thenReturn(Optional.of(started));
        when(deckRepository.findById(DECK_ID)).thenReturn(Optional.of(deck(54)));
        ConflictException e = assertThrows(ConflictException.class, () -> service.join("K7Q2XM", BOB));
        assertEquals("Game has already started", e.getMessage());
    }

    @Test
    void joiningTwiceIsHarmlessAndKeepsTheSameSeat() {
        Game g = waitingGame(4, List.of(seat(1, "ann"), seat(2, "bob")));
        when(games.isSeated(g.id(), BOB.id())).thenReturn(true);
        GameDetails d = service.join("K7Q2XM", BOB);
        verify(games, never()).addHumanSeat(any(), anyInt(), any());
        assertEquals(2, d.seats().size());
    }

    @Test
    void aSeatedPlayerCanStillOpenTheirGameAfterItStartedOrFilledUp() {
        Game started = game("K7Q2XM", 2, GameStatus.IN_PROGRESS);
        when(games.findByJoinCodeForUpdate("K7Q2XM")).thenReturn(Optional.of(started));
        when(deckRepository.findById(DECK_ID)).thenReturn(Optional.of(deck(54)));
        when(games.isSeated(started.id(), BOB.id())).thenReturn(true);
        when(games.findSeats(started.id())).thenReturn(List.of(seat(1, "ann"), seat(2, "bob")));
        assertEquals(2, service.join("K7Q2XM", BOB).seats().size());
    }

    @Test
    void joiningNormalizesTheCodeAndLocksTheGameRow() {
        waitingGame(4, List.of());
        service.join("  k7q2xm ", BOB);
        verify(games).findByJoinCodeForUpdate("K7Q2XM");
    }

    @Test
    void joiningAnUnknownGameIsNotFound() {
        when(games.findByJoinCodeForUpdate("NOPE12")).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.join("nope12", BOB));
        assertThrows(NotFoundException.class, () -> service.join(null, BOB));
    }

    @Test
    void joiningNeedsAUsername() {
        BadRequestException e = assertThrows(BadRequestException.class, () -> service.join("K7Q2XM", NO_NAME));
        assertEquals("Choose a username first", e.getMessage());
        verifyNoInteractions(games);
    }

    // ------------------------------------------------------------ get (public preview)

    @Test
    void getNormalizesTheCodeAndLoadsTheDeckWithoutTheVisibilityFilter() {
        Game g = game("K7Q2XM", 4, GameStatus.WAITING);
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
