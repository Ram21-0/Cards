package com.cards.service.service;

import com.cards.common.BadRequestException;
import com.cards.common.Card;
import com.cards.common.DifficultyLevel;
import com.cards.common.ForbiddenException;
import com.cards.common.SeatKind;
import com.cards.common.StatDefinition;
import com.cards.common.ConflictException;
import com.cards.common.Deck;
import com.cards.common.Game;
import com.cards.common.GameStatus;
import com.cards.common.NotFoundException;
import com.cards.common.Player;
import com.cards.common.SeatInfo;
import com.cards.game.GameEngine;
import com.cards.game.GameEngineFactory;
import com.cards.game.GameSettings;
import com.cards.game.SeatSpec;
import com.cards.service.db.CardRepository;
import com.cards.service.db.DeckRepository;
import com.cards.service.db.GameRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GameService {

    static final int MIN_PLAYERS = 2;
    static final int MAX_PLAYERS = 8;
    private static final int CODE_ATTEMPTS = 10;

    private final GameRepository games;
    private final DeckService decks;
    private final DeckRepository deckRepository;
    private final GameSettings defaultSettings;                 // from application.yml (app.game.*)
    private final CardRepository cardRepository;
    private final GameEngineFactory engines;
    private final Clock clock;

    /** Creates a WAITING game. The signed-in host (never a client-supplied id) takes seat 1. */
    @Transactional
    public GameDetails create(UUID deckId, Integer maxPlayers, Player host, GameOptions options) {
        requireUsername(host);
        if (deckId == null) throw new BadRequestException("deckId is required");
        if (maxPlayers == null || maxPlayers < MIN_PLAYERS || maxPlayers > MAX_PLAYERS) {
            throw new BadRequestException("maxPlayers must be between " + MIN_PLAYERS + " and " + MAX_PLAYERS);
        }

        GameSettings settings = settingsFor(options);               // 400 when an override is out of range

        Deck deck = decks.get(deckId);                              // 404 when unknown or private
        int cardCount = deck.cardCount() == null ? 0 : deck.cardCount();
        if (cardCount < maxPlayers) {
            throw new BadRequestException("This deck has " + cardCount + " cards; " + maxPlayers + " players need at least " + maxPlayers);
        }

        Game game = insertWithFreeCode(deckId, host.id(), maxPlayers, settings);
        games.addHumanSeat(game.id(), 1, host.id());
        return new GameDetails(game, deck, games.findSeats(game.id()));
    }

    /**
     * The signed-in player takes the lowest free seat. Idempotent: someone who already sits in the game just gets it back.
     * The game row is locked for the duration, so two people racing for the last seat cannot both get it.
     * 409 when the game is full or has already started.
     */
    @Transactional
    public GameDetails join(String joinCode, Player me) {
        requireUsername(me);
        String code = normalize(joinCode);
        Game game = games.findByJoinCodeForUpdate(code)
                .orElseThrow(() -> new NotFoundException("Game " + code + " not found"));
        Deck deck = deckOf(game, code);

        if (games.isSeated(game.id(), me.id())) {
            return new GameDetails(game, deck, games.findSeats(game.id()));
        }
        if (game.status() != GameStatus.WAITING) throw new ConflictException("Game has already started");

        List<SeatInfo> seats = games.findSeats(game.id());
        if (seats.size() >= game.maxPlayers()) throw new ConflictException("Game is full");

        Set<Integer> taken = seats.stream().map(SeatInfo::seat).collect(Collectors.toSet());
        int seat = 1;
        while (taken.contains(seat)) seat++;                        // lowest free seat, never above maxPlayers (checked above)
        games.addHumanSeat(game.id(), seat, me.id());
        return new GameDetails(game, deck, games.findSeats(game.id()));
    }

    // ------------------------------------------------------------------------------------------ lobby actions (host)

    /** The host adds a CPU of the given difficulty ("easy", "medium", "hard") to the lowest free seat. */
    @Transactional
    public GameDetails addCpu(String joinCode, Player host, String difficultyName) {
        String code = normalize(joinCode);
        Game game = lockedGame(code);
        requireHost(game, host);
        requireWaiting(game);
        DifficultyLevel level = parseDifficulty(difficultyName);
        List<SeatInfo> seats = games.findSeats(game.id());
        if (seats.size() >= game.maxPlayers()) throw new ConflictException("Game is full");
        games.addCpuSeat(game.id(), lowestFreeSeat(seats), level);
        return details(game, code);
    }

    /** The host removes a CPU seat. Human players leave on their own ({@link #leave}). */
    @Transactional
    public GameDetails removeSeat(String joinCode, Player host, int seat) {
        String code = normalize(joinCode);
        Game game = lockedGame(code);
        requireHost(game, host);
        requireWaiting(game);
        SeatInfo target = games.findSeats(game.id()).stream().filter(s -> s.seat() == seat).findFirst()
                .orElseThrow(() -> new NotFoundException("Seat " + seat + " is empty"));
        if (!target.isCpu()) throw new BadRequestException("Only CPU seats can be removed");
        games.deleteSeat(game.id(), seat);
        return details(game, code);
    }

    /**
     * A human leaves the lobby. If the host leaves, the lowest-seated human becomes host; if no human is left the game
     * is abandoned. Only possible before the game starts.
     */
    @Transactional
    public GameDetails leave(String joinCode, Player me) {
        String code = normalize(joinCode);
        Game game = lockedGame(code);
        List<SeatInfo> seats = games.findSeats(game.id());
        SeatInfo mine = seats.stream().filter(s -> me.id().equals(s.playerId())).findFirst()
                .orElseThrow(() -> new ConflictException("You are not in this game"));
        requireWaiting(game);
        games.deleteSeat(game.id(), mine.seat());

        Game updated = game;
        if (me.id().equals(game.hostId())) {
            seats.stream().filter(s -> s.seat() != mine.seat() && s.playerId() != null).findFirst().ifPresentOrElse(
                    next -> games.setHost(game.id(), next.playerId()),
                    () -> games.setStatus(game.id(), GameStatus.ABANDONED));
            updated = games.findByJoinCode(code).orElseThrow(() -> new NotFoundException("Game " + code + " not found"));
        }
        return details(updated, code);
    }

    /**
     * The host starts the game: needs at least 2 seats. Seat numbers are closed up to 1..n, the rules engine is created
     * from the deck's cards, the seats and the game's settings, and the game moves to IN_PROGRESS.
     */
    @Transactional
    public StartedGame start(String joinCode, Player host) {
        String code = normalize(joinCode);
        Game game = lockedGame(code);
        requireHost(game, host);
        requireWaiting(game);
        List<SeatInfo> seats = games.findSeats(game.id());
        if (seats.size() < MIN_PLAYERS) throw new ConflictException("At least " + MIN_PLAYERS + " players are needed to start");

        Deck deck = deckOf(game, code);
        List<Card> cards = cardRepository.findByDeck(deck.id());
        if (cards.size() < seats.size()) throw new ConflictException("This deck has fewer cards than seats");
        for (Card card : cards) {
            for (StatDefinition stat : deck.stats()) {
                if (card.stats().stream().noneMatch(s -> s.key().equals(stat.key()))) {
                    throw new ConflictException("Some cards of this deck are missing stats, so it cannot be played yet");
                }
            }
        }

        games.renumberSeats(game.id());
        List<SeatInfo> numbered = games.findSeats(game.id());
        List<SeatSpec> specs = numbered.stream().map(s -> s.isCpu() ? SeatSpec.cpu(s.difficulty()) : SeatSpec.human()).toList();
        GameSettings settings = new GameSettings(game.maxRounds(), game.chooseThreshold(), game.clashCallSeconds(), game.turnTimeoutSeconds());
        GameEngine engine = engines.create(cards, deck.stats(), specs, settings);

        games.markStarted(game.id(), clock.instant());
        Game started = games.findByJoinCode(code).orElseThrow(() -> new NotFoundException("Game " + code + " not found"));
        return new StartedGame(new GameDetails(started, deck, numbered), engine);
    }

    /** Looks a game up by its shareable code (case-insensitive). Public: this is the join-link preview. */
    @Transactional(readOnly = true)
    public GameDetails get(String joinCode) {
        String code = normalize(joinCode);
        Game game = games.findByJoinCode(code)
                .orElseThrow(() -> new NotFoundException("Game " + code + " not found"));
        return new GameDetails(game, deckOf(game, code), games.findSeats(game.id()));
    }

    /** Seats show usernames, so people must have chosen one (PUT /api/me) before they create or join. */
    private static void requireUsername(Player player) {
        if (player.needsUsername()) throw new BadRequestException("Choose a username first");
    }

    private static String normalize(String joinCode) {
        return joinCode == null ? "" : joinCode.trim().toUpperCase(Locale.ROOT);
    }

    /** Joiners must see the deck name even if the deck is private, so no visibility filter here. */
    private Deck deckOf(Game game, String code) {
        return deckRepository.findById(game.deckId())
                .orElseThrow(() -> new NotFoundException("Deck of game " + code + " not found"));
    }

    /** The host's overrides on top of the defaults, range-checked (RULES.md §1). */
    private GameSettings settingsFor(GameOptions options) {
        GameOptions o = options == null ? GameOptions.NONE : options;
        try {
            return GameSettings.resolve(o.maxRounds(), o.chooseThreshold(), o.clashCallSeconds(), o.turnTimeoutSeconds(), defaultSettings);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
    }

    private Game lockedGame(String code) {
        return games.findByJoinCodeForUpdate(code).orElseThrow(() -> new NotFoundException("Game " + code + " not found"));
    }

    private GameDetails details(Game game, String code) {
        return new GameDetails(game, deckOf(game, code), games.findSeats(game.id()));
    }

    private static void requireHost(Game game, Player player) {
        if (!player.id().equals(game.hostId())) throw new ForbiddenException("Only the host can do that");
    }

    private static void requireWaiting(Game game) {
        if (game.status() != GameStatus.WAITING) throw new ConflictException("Game has already started");
    }

    private static DifficultyLevel parseDifficulty(String name) {
        for (DifficultyLevel level : DifficultyLevel.values()) {
            if (level.name().equalsIgnoreCase(name == null ? "" : name.trim())) return level;
        }
        throw new BadRequestException("difficulty must be easy, medium or hard");
    }

    private static int lowestFreeSeat(List<SeatInfo> seats) {
        Set<Integer> taken = seats.stream().map(SeatInfo::seat).collect(Collectors.toSet());
        int seat = 1;
        while (taken.contains(seat)) seat++;
        return seat;
    }

    private Game insertWithFreeCode(UUID deckId, UUID hostId, int maxPlayers, GameSettings settings) {
        for (int attempt = 0; attempt < CODE_ATTEMPTS; attempt++) {
            var created = games.insertIfCodeFree(JoinCodes.next(), deckId, hostId, maxPlayers, settings);
            if (created.isPresent()) return created.get();
        }
        throw new IllegalStateException("Could not find a free join code");
    }
}
