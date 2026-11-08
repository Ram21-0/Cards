package com.cards.service.service;

import com.cards.TestClock;
import com.cards.common.BadRequestException;
import com.cards.common.Card;
import com.cards.common.ComparisonStrategy;
import com.cards.common.DifficultyLevel;
import com.cards.common.DisplayFormat;
import com.cards.common.ForbiddenException;
import com.cards.common.Stat;
import com.cards.common.StatDefinition;
import com.cards.common.ValueType;
import com.cards.common.ConflictException;
import com.cards.common.Deck;
import com.cards.common.Game;
import com.cards.common.GameStatus;
import com.cards.common.NotFoundException;
import com.cards.common.Player;
import com.cards.common.SeatInfo;
import com.cards.common.SeatKind;
import com.cards.common.Visibility;
import com.cards.game.GameEngine;
import com.cards.game.GameEngineFactory;
import com.cards.game.GameSettings;
import com.cards.game.SeatSpec;
import com.cards.service.db.CardRepository;
import com.cards.service.db.DeckRepository;
import com.cards.service.db.GameRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    private final CardRepository cardRepository = mock(CardRepository.class);
    private final GameEngineFactory engines = mock(GameEngineFactory.class);
    private final TestClock clock = TestClock.atNow();
    private final GameService service = new GameService(games, decks, deckRepository, new GameSettings(300, 2, 8, 30),
            cardRepository, engines, clock);

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
        assertThrows(BadRequestException.class, () -> service.create(null, 4, ANN, GameOptions.NONE));
        verifyNoInteractions(games, decks);
    }

    @Test
    void maxPlayersMustBeBetweenTwoAndEight() {
        for (Integer bad : new Integer[]{null, 0, 1, 9, 100}) {
            assertThrows(BadRequestException.class, () -> service.create(DECK_ID, bad, ANN, GameOptions.NONE), "maxPlayers " + bad);
        }
        verifyNoInteractions(games, decks);
        // the edges are fine
        when(decks.get(DECK_ID)).thenReturn(deck(54));
        when(games.insertIfCodeFree(anyString(), any(), any(), anyInt(), any())).thenAnswer(i -> Optional.of(game(i.getArgument(0), 4, GameStatus.WAITING)));
        service.create(DECK_ID, 2, ANN, GameOptions.NONE);
        service.create(DECK_ID, 8, ANN, GameOptions.NONE);
    }

    @Test
    void deckNeedsAtLeastOneCardPerPlayer() {
        when(decks.get(DECK_ID)).thenReturn(deck(3));
        BadRequestException e = assertThrows(BadRequestException.class, () -> service.create(DECK_ID, 4, ANN, GameOptions.NONE));
        assertEquals("This deck has 3 cards; 4 players need at least 4", e.getMessage());
        verify(games, never()).insertIfCodeFree(anyString(), any(), any(), anyInt(), any());
    }

    @Test
    void unknownOrPrivateDeckIsNotFound() {
        when(decks.get(DECK_ID)).thenThrow(new NotFoundException("Deck not found"));
        assertThrows(NotFoundException.class, () -> service.create(DECK_ID, 4, ANN, GameOptions.NONE));
    }

    @Test
    void thePlayerMustHaveChosenAUsername() {
        BadRequestException e = assertThrows(BadRequestException.class, () -> service.create(DECK_ID, 4, NO_NAME, GameOptions.NONE));
        assertEquals("Choose a username first", e.getMessage());
        verifyNoInteractions(games, decks);
    }

    @Test
    void theSignedInHostIsTheGameHostAndTakesSeatOne() {
        when(decks.get(DECK_ID)).thenReturn(deck(54));
        Game created = game("K7Q2XM", 4, GameStatus.WAITING);
        when(games.insertIfCodeFree(anyString(), eq(DECK_ID), eq(ANN.id()), eq(4), any())).thenReturn(Optional.of(created));
        List<SeatInfo> seats = List.of(seat(1, "ann"));
        when(games.findSeats(created.id())).thenReturn(seats);

        GameDetails d = service.create(DECK_ID, 4, ANN, GameOptions.NONE);

        verify(games).addHumanSeat(created.id(), 1, ANN.id());
        assertEquals(seats, d.seats());
        assertEquals("K7Q2XM", d.game().joinCode());
    }

    @Test
    void retriesWithAnotherCodeOnCollision() {
        when(decks.get(DECK_ID)).thenReturn(deck(54));
        when(games.insertIfCodeFree(anyString(), any(), any(), anyInt(), any()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty())
                .thenAnswer(i -> Optional.of(game(i.getArgument(0), 4, GameStatus.WAITING)));
        service.create(DECK_ID, 4, ANN, GameOptions.NONE);
        verify(games, times(3)).insertIfCodeFree(anyString(), any(), any(), anyInt(), any());
    }

    @Test
    void givesUpWhenNoCodeIsFree() {
        when(decks.get(DECK_ID)).thenReturn(deck(54));
        when(games.insertIfCodeFree(anyString(), any(), any(), anyInt(), any())).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> service.create(DECK_ID, 4, ANN, GameOptions.NONE));
    }

    // ------------------------------------------------------------ lobby actions (host / leave / start)

    private static SeatInfo humanSeat(int n, Player p) {
        return new SeatInfo(n, SeatKind.HUMAN, p.username(), null, p.id(), null);
    }

    private static SeatInfo cpuSeat(int n, DifficultyLevel level) {
        return new SeatInfo(n, SeatKind.CPU, null, level, null, null);
    }

    /** A WAITING game hosted by ANN, locked for update as the service reads it. */
    private Game lobby(int maxPlayers, GameStatus status, List<SeatInfo> seats) {
        Game g = Game.builder().id(UUID.randomUUID()).joinCode("K7Q2XM").deckId(DECK_ID).hostId(ANN.id()).maxPlayers(maxPlayers)
                .maxRounds(300).chooseThreshold(2).clashCallSeconds(8).turnTimeoutSeconds(30).status(status).build();
        when(games.findByJoinCodeForUpdate("K7Q2XM")).thenReturn(Optional.of(g));
        when(games.findByJoinCode("K7Q2XM")).thenReturn(Optional.of(g));
        when(deckRepository.findById(DECK_ID)).thenReturn(Optional.of(deck(54)));
        when(games.findSeats(g.id())).thenReturn(seats);
        return g;
    }

    @Test
    void theHostAddsACpuToTheLowestFreeSeat() {
        Game g = lobby(4, GameStatus.WAITING, List.of(humanSeat(1, ANN), humanSeat(3, BOB)));
        service.addCpu("k7q2xm", ANN, "HARD");
        verify(games).addCpuSeat(g.id(), 2, DifficultyLevel.hard);
    }

    @Test
    void onlyTheHostMayAddACpu() {
        lobby(4, GameStatus.WAITING, List.of(humanSeat(1, ANN), humanSeat(2, BOB)));
        assertThrows(ForbiddenException.class, () -> service.addCpu("K7Q2XM", BOB, "easy"));
        verify(games, never()).addCpuSeat(any(), anyInt(), any());
    }

    @Test
    void aCpuCannotBeAddedToAFullOrStartedGameOrWithABadDifficulty() {
        lobby(2, GameStatus.WAITING, List.of(humanSeat(1, ANN), humanSeat(2, BOB)));
        assertEquals("Game is full", assertThrows(ConflictException.class, () -> service.addCpu("K7Q2XM", ANN, "easy")).getMessage());

        lobby(4, GameStatus.IN_PROGRESS, List.of(humanSeat(1, ANN)));
        assertEquals("Game has already started", assertThrows(ConflictException.class, () -> service.addCpu("K7Q2XM", ANN, "easy")).getMessage());

        lobby(4, GameStatus.WAITING, List.of(humanSeat(1, ANN)));
        for (String bad : new String[]{null, "", "impossible", "5"}) {
            assertEquals("difficulty must be easy, medium, hard or extreme", assertThrows(BadRequestException.class, () -> service.addCpu("K7Q2XM", ANN, bad)).getMessage());
        }
        verify(games, never()).addCpuSeat(any(), anyInt(), any());
    }

    @Test
    void theHostRemovesACpuSeatButNotAHuman() {
        Game g = lobby(4, GameStatus.WAITING, List.of(humanSeat(1, ANN), humanSeat(2, BOB), cpuSeat(3, DifficultyLevel.easy)));
        service.removeSeat("K7Q2XM", ANN, 3);
        verify(games).deleteSeat(g.id(), 3);
        assertEquals("Only CPU seats can be removed", assertThrows(BadRequestException.class, () -> service.removeSeat("K7Q2XM", ANN, 2)).getMessage());
        assertThrows(NotFoundException.class, () -> service.removeSeat("K7Q2XM", ANN, 4));
        assertThrows(ForbiddenException.class, () -> service.removeSeat("K7Q2XM", BOB, 3));
        verify(games).deleteSeat(any(), anyInt());                              // only the first removal happened
    }

    @Test
    void aSeatCannotBeRemovedOnceTheGameStarted() {
        lobby(4, GameStatus.IN_PROGRESS, List.of(humanSeat(1, ANN), cpuSeat(2, DifficultyLevel.easy)));
        assertThrows(ConflictException.class, () -> service.removeSeat("K7Q2XM", ANN, 2));
    }

    @Test
    void aPlayerLeavesAndTheHostStaysTheHost() {
        Game g = lobby(4, GameStatus.WAITING, List.of(humanSeat(1, ANN), humanSeat(2, BOB)));
        service.leave("K7Q2XM", BOB);
        verify(games).deleteSeat(g.id(), 2);
        verify(games, never()).setHost(any(), any());
        verify(games, never()).setStatus(any(), any());
    }

    @Test
    void whenTheHostLeavesTheNextHumanBecomesHost() {
        Game g = lobby(4, GameStatus.WAITING, List.of(humanSeat(1, ANN), cpuSeat(2, DifficultyLevel.easy), humanSeat(3, BOB)));
        service.leave("K7Q2XM", ANN);
        verify(games).deleteSeat(g.id(), 1);
        verify(games).setHost(g.id(), BOB.id());
        verify(games, never()).setStatus(any(), any());
    }

    @Test
    void whenTheLastHumanLeavesTheGameIsAbandoned() {
        Game g = lobby(4, GameStatus.WAITING, List.of(humanSeat(1, ANN), cpuSeat(2, DifficultyLevel.hard)));
        service.leave("K7Q2XM", ANN);
        verify(games).deleteSeat(g.id(), 1);
        verify(games).setStatus(g.id(), GameStatus.ABANDONED);
    }

    @Test
    void youCannotLeaveAGameYouAreNotInOrOneThatStarted() {
        lobby(4, GameStatus.WAITING, List.of(humanSeat(1, ANN)));
        assertEquals("You are not in this game", assertThrows(ConflictException.class, () -> service.leave("K7Q2XM", BOB)).getMessage());
        lobby(4, GameStatus.IN_PROGRESS, List.of(humanSeat(1, ANN), humanSeat(2, BOB)));
        assertEquals("Game has already started", assertThrows(ConflictException.class, () -> service.leave("K7Q2XM", BOB)).getMessage());
        verify(games, never()).deleteSeat(any(), anyInt());
    }

    // ---- start

    private static Card plainCard(int id, boolean withPower) {
        return Card.builder().id(id).deckId(DECK_ID).name("C" + id).metadata(java.util.Map.of())
                .stats(withPower ? List.of(new Stat("power", BigDecimal.valueOf(id))) : List.of()).build();
    }

    private static Deck deckWithPower() {
        StatDefinition power = StatDefinition.builder().deckId(DECK_ID).key("power").label("Power").valueType(ValueType.INT)
                .comparisonStrategy(ComparisonStrategy.HIGHER_WINS).displayFormat(DisplayFormat.PLAIN).displayOrder(1).build();
        return Deck.builder().id(DECK_ID).name("Wrestling").visibility(Visibility.PUBLIC).cardCount(6).stats(List.of(power)).build();
    }

    @Test
    void startingNeedsTheHostTwoSeatsAndAWaitingGame() {
        lobby(4, GameStatus.WAITING, List.of(humanSeat(1, ANN), humanSeat(2, BOB)));
        assertThrows(ForbiddenException.class, () -> service.start("K7Q2XM", BOB));

        lobby(4, GameStatus.WAITING, List.of(humanSeat(1, ANN)));
        assertEquals("At least 2 players are needed to start", assertThrows(ConflictException.class, () -> service.start("K7Q2XM", ANN)).getMessage());

        lobby(4, GameStatus.IN_PROGRESS, List.of(humanSeat(1, ANN), humanSeat(2, BOB)));
        assertEquals("Game has already started", assertThrows(ConflictException.class, () -> service.start("K7Q2XM", ANN)).getMessage());
        verify(games, never()).markStarted(any(), any());
        verifyNoInteractions(engines);
    }

    @Test
    void aDeckWithTooFewOrIncompleteCardsCannotBeStarted() {
        Game g = lobby(4, GameStatus.WAITING, List.of(humanSeat(1, ANN), humanSeat(2, BOB), cpuSeat(3, DifficultyLevel.easy)));
        when(deckRepository.findById(DECK_ID)).thenReturn(Optional.of(deckWithPower()));
        when(cardRepository.findByDeck(DECK_ID)).thenReturn(List.of(plainCard(1, true), plainCard(2, true)));
        assertEquals("This deck has fewer cards than seats", assertThrows(ConflictException.class, () -> service.start("K7Q2XM", ANN)).getMessage());

        when(cardRepository.findByDeck(DECK_ID)).thenReturn(List.of(plainCard(1, true), plainCard(2, true), plainCard(3, false)));
        assertTrue(assertThrows(ConflictException.class, () -> service.start("K7Q2XM", ANN)).getMessage().contains("missing stats"));
        verify(games, never()).renumberSeats(g.id());
        verify(games, never()).markStarted(any(), any());
    }

    @Test
    void startingCreatesTheEngineFromTheSeatsAndTheGamesSettings() {
        Game g = lobby(4, GameStatus.WAITING, List.of(humanSeat(1, ANN), cpuSeat(3, DifficultyLevel.hard), humanSeat(4, BOB)));
        when(deckRepository.findById(DECK_ID)).thenReturn(Optional.of(deckWithPower()));
        List<Card> cards = List.of(plainCard(1, true), plainCard(2, true), plainCard(3, true), plainCard(4, true));
        when(cardRepository.findByDeck(DECK_ID)).thenReturn(cards);
        GameEngine engine = mock(GameEngine.class);
        when(engines.create(any(), any(), any(), any())).thenReturn(engine);
        // after renumbering the seats are 1, 2, 3
        when(games.findSeats(g.id())).thenReturn(List.of(humanSeat(1, ANN), humanSeat(3, BOB)))                 // before: with a gap
                .thenReturn(List.of(humanSeat(1, ANN), cpuSeat(2, DifficultyLevel.hard), humanSeat(3, BOB)));    // after renumbering

        StartedGame started = service.start("K7Q2XM", ANN);

        assertEquals(engine, started.engine());
        ArgumentCaptor<List<SeatSpec>> specs = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<GameSettings> settings = ArgumentCaptor.forClass(GameSettings.class);
        verify(games).renumberSeats(g.id());
        verify(engines).create(eq(cards), any(), specs.capture(), settings.capture());
        assertEquals(List.of(SeatSpec.human(), SeatSpec.cpu(DifficultyLevel.hard), SeatSpec.human()), specs.getValue());
        assertEquals(new GameSettings(300, 2, 8, 30), settings.getValue(), "the settings stored with the game");
        verify(games).markStarted(g.id(), clock.instant());
        assertEquals(3, started.details().seats().size());
    }

    // ------------------------------------------------------------ the rule settings (RULES.md §1)

    private GameSettings createdWith(GameOptions options) {
        when(decks.get(DECK_ID)).thenReturn(deck(54));
        when(games.insertIfCodeFree(anyString(), any(), any(), anyInt(), any())).thenAnswer(i -> Optional.of(game(i.getArgument(0), 4, GameStatus.WAITING)));
        service.create(DECK_ID, 4, ANN, options);
        ArgumentCaptor<GameSettings> captured = ArgumentCaptor.forClass(GameSettings.class);
        verify(games).insertIfCodeFree(anyString(), eq(DECK_ID), eq(ANN.id()), eq(4), captured.capture());
        return captured.getValue();
    }

    @Test
    void withoutOptionsTheDefaultsFromTheConfigurationAreUsed() {
        assertEquals(new GameSettings(300, 2, 8, 30), createdWith(GameOptions.NONE));
    }

    @Test
    void aMissingOptionsObjectMeansTheSameAsEmptyOptions() {
        assertEquals(new GameSettings(300, 2, 8, 30), createdWith(null));
    }

    @Test
    void theHostsOverridesWinAndTheRestKeepTheDefaults() {
        assertEquals(new GameSettings(100, 2, 8, 30), createdWith(new GameOptions(100, null, null, null)));
    }

    @Test
    void zeroIsARealOverride() {
        assertEquals(new GameSettings(300, 0, 8, 30), createdWith(new GameOptions(null, 0, null, null)), "CHOOSE switched off");
    }

    @Test
    void everyOverrideIsRangeCheckedBeforeAnythingIsCreated() {
        String[][] bad = {{"maxRounds must be between 20 and 5000", "19", "x", "x", "x"}, {"chooseThreshold must be between 0 and 10", "x", "11", "x", "x"},
                {"clashCallSeconds must be between 2 and 30", "x", "x", "1", "x"}, {"turnTimeoutSeconds must be between 5 and 120", "x", "x", "x", "4"}};
        GameOptions[] options = {new GameOptions(19, null, null, null), new GameOptions(null, 11, null, null),
                new GameOptions(null, null, 1, null), new GameOptions(null, null, null, 4)};
        for (int i = 0; i < options.length; i++) {
            GameOptions o = options[i];
            BadRequestException e = assertThrows(BadRequestException.class, () -> service.create(DECK_ID, 4, ANN, o));
            assertEquals(bad[i][0], e.getMessage());
        }
        verify(games, never()).insertIfCodeFree(anyString(), any(), any(), anyInt(), any());
        verify(games, never()).addHumanSeat(any(), anyInt(), any());
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
