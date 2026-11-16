package com.cards.service.db;

import com.cards.deckimport.DeckCardImport;
import com.cards.common.Card;
import com.cards.common.Stat;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Reads cards. The jsonb columns `stats` and `meta` are parsed into Stat objects / a metadata map. */
@Repository
@RequiredArgsConstructor
public class CardRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public List<Card> findByDeck(UUID deckId) {
        return jdbc.query("select * from cards where deck_id = ? order by id", this::map, deckId);
    }

    public int countByDeck(UUID deckId) {
        return jdbc.queryForObject("select count(*) from cards where deck_id = ?", Integer.class, deckId);
    }

    /** Replaces ALL cards of the deck with these (the stat import). The caller has validated them; stats are stored under the Wrestling keys. */
    public void replaceAll(UUID deckId, List<DeckCardImport> imported) {
        jdbc.update("delete from cards where deck_id = ?", deckId);
        jdbc.batchUpdate("insert into cards (deck_id, name, stats, meta) values (?, ?, ?::jsonb, ?::jsonb)", imported, 100, (ps, c) -> {
            ps.setObject(1, deckId);
            ps.setString(2, c.name());
            ps.setString(3, write(statsOf(c)));
            ps.setString(4, write(c.brand() == null ? Map.of() : Map.of("brand", c.brand())));
        });
    }

    private static Map<String, Object> statsOf(DeckCardImport c) {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("rank", c.rank());
        stats.put("fight", c.fight());
        stats.put("chest", c.chest());
        stats.put("biceps", c.biceps());
        stats.put("height", c.height());
        stats.put("weight", c.weight());
        return stats;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Card map(ResultSet rs, int rowNum) throws SQLException {
        return Card.builder()
                .id(rs.getInt("id"))
                .deckId(rs.getObject("deck_id", UUID.class))
                .name(rs.getString("name"))
                .imageUrl(rs.getString("img_url"))
                .stats(parseStats(rs.getString("stats")))
                .metadata(parse(rs.getString("meta"), new TypeReference<Map<String, Object>>() {}))
                .build();
    }

    private List<Stat> parseStats(String text) {
        Map<String, BigDecimal> values = parse(text, new TypeReference<Map<String, BigDecimal>>() {});
        return values.entrySet().stream().map(e -> new Stat(e.getKey(), e.getValue())).toList();
    }

    private <T> T parse(String text, TypeReference<T> type) {
        try {
            return json.readValue(text, type);
        } catch (Exception e) {
            throw new IllegalStateException("Bad JSON in cards table: " + text, e);
        }
    }
}
