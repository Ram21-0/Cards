package com.cards.service.service;

import com.cards.common.BadRequestException;
import com.cards.common.Deck;
import com.cards.common.Game;
import com.cards.common.NotFoundException;
import com.cards.service.db.DeckRepository;
import com.cards.service.db.GameRepository;
import com.cards.service.db.PlayerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GameService {

    static final int MIN_PLAYERS = 2;
    static final int MAX_PLAYERS = 8;
    private static final int CODE_ATTEMPTS = 10;

    private final GameRepository games;
    private final DeckService decks;
    private final DeckRepository deckRepository;
    private final PlayerRepository players;

    /** Creates a WAITING game; the host (if given) takes seat 1. */
    @Transactional
    public GameDetails create(UUID deckId, Integer maxPlayers, UUID hostId) {
        if (deckId == null) throw new BadRequestException("deckId is required");
        if (maxPlayers == null || maxPlayers < MIN_PLAYERS || maxPlayers > MAX_PLAYERS) {
            throw new BadRequestException("maxPlayers must be between " + MIN_PLAYERS + " and " + MAX_PLAYERS);
        }

        Deck deck = decks.get(deckId);                              // 404 when unknown or private
        int cardCount = deck.cardCount() == null ? 0 : deck.cardCount();
        if (cardCount < maxPlayers) {
            throw new BadRequestException("This deck has " + cardCount + " cards; " + maxPlayers + " players need at least " + maxPlayers);
        }
        if (hostId != null && players.findById(hostId).isEmpty()) {
            throw new NotFoundException("Player " + hostId + " not found");
        }

        Game game = insertWithFreeCode(deckId, hostId, maxPlayers);
        if (hostId != null) games.addHumanSeat(game.id(), 1, hostId);
        return new GameDetails(game, deck, games.findSeats(game.id()));
    }

    /** Looks a game up by its shareable code (case-insensitive). */
    @Transactional(readOnly = true)
    public GameDetails get(String joinCode) {
        String code = joinCode == null ? "" : joinCode.trim().toUpperCase(Locale.ROOT);
        Game game = games.findByJoinCode(code)
                .orElseThrow(() -> new NotFoundException("Game " + code + " not found"));
        // Joiners must see the deck name even if the deck is private, so no visibility filter here.
        Deck deck = deckRepository.findById(game.deckId())
                .orElseThrow(() -> new NotFoundException("Deck of game " + code + " not found"));
        return new GameDetails(game, deck, games.findSeats(game.id()));
    }

    private Game insertWithFreeCode(UUID deckId, UUID hostId, int maxPlayers) {
        for (int attempt = 0; attempt < CODE_ATTEMPTS; attempt++) {
            var created = games.insertIfCodeFree(JoinCodes.next(), deckId, hostId, maxPlayers);
            if (created.isPresent()) return created.get();
        }
        throw new IllegalStateException("Could not find a free join code");
    }
}
