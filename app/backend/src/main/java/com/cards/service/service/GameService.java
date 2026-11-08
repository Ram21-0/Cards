package com.cards.service.service;

import com.cards.common.BadRequestException;
import com.cards.common.ConflictException;
import com.cards.common.Deck;
import com.cards.common.Game;
import com.cards.common.GameStatus;
import com.cards.common.NotFoundException;
import com.cards.common.Player;
import com.cards.common.SeatInfo;
import com.cards.service.db.DeckRepository;
import com.cards.service.db.GameRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /** Creates a WAITING game. The signed-in host (never a client-supplied id) takes seat 1. */
    @Transactional
    public GameDetails create(UUID deckId, Integer maxPlayers, Player host) {
        requireUsername(host);
        if (deckId == null) throw new BadRequestException("deckId is required");
        if (maxPlayers == null || maxPlayers < MIN_PLAYERS || maxPlayers > MAX_PLAYERS) {
            throw new BadRequestException("maxPlayers must be between " + MIN_PLAYERS + " and " + MAX_PLAYERS);
        }

        Deck deck = decks.get(deckId);                              // 404 when unknown or private
        int cardCount = deck.cardCount() == null ? 0 : deck.cardCount();
        if (cardCount < maxPlayers) {
            throw new BadRequestException("This deck has " + cardCount + " cards; " + maxPlayers + " players need at least " + maxPlayers);
        }

        Game game = insertWithFreeCode(deckId, host.id(), maxPlayers);
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

    private Game insertWithFreeCode(UUID deckId, UUID hostId, int maxPlayers) {
        for (int attempt = 0; attempt < CODE_ATTEMPTS; attempt++) {
            var created = games.insertIfCodeFree(JoinCodes.next(), deckId, hostId, maxPlayers);
            if (created.isPresent()) return created.get();
        }
        throw new IllegalStateException("Could not find a free join code");
    }
}
