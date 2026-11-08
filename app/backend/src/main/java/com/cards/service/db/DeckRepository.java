package com.cards.service.db;

import com.cards.common.ComparisonStrategy;
import com.cards.common.Deck;
import com.cards.common.StatDefinition;
import com.cards.common.ValueType;
import com.cards.common.Visibility;
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

    private static final RowMapper<Deck> DECK = (rs, i) -> Deck.builder()
            .id(rs.getObject("id", UUID.class))
            .ownerId(rs.getObject("owner_id", UUID.class))
            .name(rs.getString("name"))
            .description(rs.getString("description"))
            .visibility(Visibility.get(rs.getInt("visibility")))
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
            .displayOrder(rs.getInt("display_order"))
            .build();

    public List<Deck> findPublic() {
        return jdbc.query("select * from decks where visibility = ? order by name", DECK, Visibility.PUBLIC.dbVal())
                .stream().map(this::withStats).toList();
    }

    public Optional<Deck> findById(UUID id) {
        return jdbc.query("select * from decks where id = ?", DECK, id)
                .stream().findFirst().map(this::withStats);
    }

    private Deck withStats(Deck deck) {
        List<StatDefinition> stats = jdbc.query(
                "select * from deck_stats where deck_id = ? order by display_order, id", STAT, deck.id());
        return deck.toBuilder().stats(stats).build();
    }
}
