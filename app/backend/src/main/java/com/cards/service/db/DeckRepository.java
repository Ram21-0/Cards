package com.cards.service.db;

import com.cards.common.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reads decks and their stat definitions. Plain SQL via JdbcTemplate; rows -> com.cards.common model classes. */
@Repository
@RequiredArgsConstructor
public class DeckRepository {

    private final JdbcTemplate jdbc;

    private static final String CARD_COUNT_SELECT =
            "select d.*, (select count(*) from cards c where c.deck_id = d.id) as card_count from decks d";

    private static final RowMapper<Deck> DECK = (rs, i) -> Deck.builder()
            .id(rs.getObject("id", UUID.class))
            .ownerId(rs.getObject("owner_id", UUID.class))
            .name(rs.getString("name"))
            .description(rs.getString("description"))
            .visibility(Visibility.get(rs.getInt("visibility")))
            .cardCount(rs.getInt("card_count"))
            .live(rs.getBoolean("live"))
            .createdAt(rs.getTimestamp("created_at").toInstant())
            .updatedAt(rs.getTimestamp("updated_at").toInstant())
            .build();

    private static final RowMapper<StatDefinition> STAT = (rs, i) -> StatDefinition.builder()
            .id(rs.getInt("id"))
            .deckId(rs.getObject("deck_id", UUID.class))
            .key(rs.getString("key"))
            .label(rs.getString("label"))
            .unit(rs.getString("unit"))
            .valueType(ValueType.get(rs.getInt("value_type")))
            .comparisonStrategy(ComparisonStrategy.get(rs.getInt("comparison_strategy")))
            .displayFormat(DisplayFormat.get(rs.getInt("display_format")))
            .displayOrder(rs.getInt("display_order"))
            .build();

    public List<Deck> findPublic() {
        return jdbc.query(CARD_COUNT_SELECT + " where d.visibility = ? and d.live and exists (select 1 from deck_stats s where s.deck_id = d.id) order by d.name", DECK, Visibility.PUBLIC.dbVal())
                .stream().map(this::withStats).toList();
    }

    public Optional<Deck> findById(UUID id) {
        return jdbc.query(CARD_COUNT_SELECT + " where d.id = ?", DECK, id)
                .stream().findFirst().map(this::withStats);
    }

    public UUID insertDeck(UUID ownerId, String name, String description, Visibility visibility, boolean live) {
        return jdbc.queryForObject("insert into decks (name, description, visibility, owner_id, live) values (?, ?, ?, ?, ?) returning id",
                UUID.class, name, description, visibility.dbVal(), ownerId, live);
    }

    /** The stat definitions of an upload, in column order (display_order 1..n). */
    public void insertStats(UUID deckId, List<com.cards.deckimport.StatSpec> specs) {
        int order = 0;
        for (com.cards.deckimport.StatSpec s : specs) {
            order++;
            jdbc.update("""
                    insert into deck_stats (deck_id, key, label, unit, value_type, comparison_strategy, display_order, display_format)
                    values (?, ?, ?, ?, ?, ?, ?, ?)""", deckId, s.key(), s.label(), s.unit(), s.valueType().dbVal(),
                    (s.higherWins() ? ComparisonStrategy.HIGHER_WINS : ComparisonStrategy.LOWER_WINS).dbVal(), order, s.displayFormat().dbVal());
        }
    }

    public void deleteStats(UUID deckId) {
        jdbc.update("delete from deck_stats where deck_id = ?", deckId);
    }

    public void deleteDeck(UUID deckId) {
        jdbc.update("delete from decks where id = ?", deckId);
    }

    public void rename(UUID deckId, String name, String description) {
        jdbc.update("update decks set name = ?, description = ?, updated_at = now() where id = ?", name, description, deckId);
    }

    /** All decks a player owns (any visibility, live or not), newest first. */
    public List<Deck> findOwnedBy(UUID ownerId) {
        return jdbc.query(CARD_COUNT_SELECT + " where d.owner_id = ? order by d.created_at desc", DECK, ownerId).stream().map(this::withStats).toList();
    }

    public int countOwnedBy(UUID ownerId) {
        return jdbc.queryForObject("select count(*) from decks where owner_id = ?", Integer.class, ownerId);
    }

    /** Games (any state) keep their deck, so a deck that was ever used cannot be deleted. */
    public boolean isUsedByGames(UUID deckId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from games where deck_id = ?)", Boolean.class, deckId));
    }

    /** An approved request's deck: owned by the requester, PRIVATE and NOT live (no stats yet, so it is never listed or playable). */
    public UUID insertNotLive(UUID ownerId, String name, String description) {
        return jdbc.queryForObject("insert into decks (name, description, visibility, owner_id, live) values (?, ?, ?, ?, false) returning id",
                UUID.class, name, description, Visibility.PRIVATE.dbVal(), ownerId);
    }

    /** Public decks plus the player's own PRIVATE ones, live and with stats only. */
    public List<Deck> findVisibleTo(UUID playerId) {
        return jdbc.query(CARD_COUNT_SELECT + " where d.live and exists (select 1 from deck_stats s where s.deck_id = d.id)"
                        + " and (d.visibility = ? or (d.visibility = ? and d.owner_id = ?)) order by d.name",
                        DECK, Visibility.PUBLIC.dbVal(), Visibility.PRIVATE.dbVal(), playerId)
                .stream().map(this::withStats).toList();
    }

    /** Is this player in a game that is being played or waiting with this deck? (Then they may read it even if it is private.) */
    public boolean isSeatedWithDeck(UUID playerId, UUID deckId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from game_players gp join games g on g.id = gp.game_id
                               where g.deck_id = ? and gp.player_id = ? and g.status in (0, 1))""", Boolean.class, deckId, playerId));
    }

    /** The built-in deck whose stat definitions new decks copy (by name, platform-owned). */
    public Optional<UUID> findTemplateId(String name) {
        return jdbc.query("select id from decks where name = ? and owner_id is null order by created_at limit 1", (rs, i) -> rs.getObject("id", UUID.class), name)
                .stream().findFirst();
    }

    public int statCount(UUID deckId) {
        return jdbc.queryForObject("select count(*) from deck_stats where deck_id = ?", Integer.class, deckId);
    }

    /** Gives the deck the same stat definitions (keys, labels, units, strategies, order, formats) as the template deck. */
    public void copyStats(UUID templateDeckId, UUID deckId) {
        jdbc.update("""
                insert into deck_stats (deck_id, key, label, unit, value_type, comparison_strategy, display_order, display_format)
                select ?, key, label, unit, value_type, comparison_strategy, display_order, display_format from deck_stats where deck_id = ?""",
                deckId, templateDeckId);
    }

    /** Goes live with the visibility the admin chose. */
    public void publish(UUID deckId, Visibility visibility, java.time.Instant now) {
        jdbc.update("update decks set visibility = ?, live = true, updated_at = ? where id = ?", visibility.dbVal(), java.sql.Timestamp.from(now), deckId);
    }

    private Deck withStats(Deck deck) {
        List<StatDefinition> stats = jdbc.query(
                "select * from deck_stats where deck_id = ? order by display_order, id", STAT, deck.id());
        return deck.toBuilder().stats(stats).build();
    }
}
