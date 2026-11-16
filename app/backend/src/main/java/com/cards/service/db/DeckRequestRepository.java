package com.cards.service.db;

import com.cards.common.DeckRequest;
import com.cards.common.DeckRequestStatus;
import com.cards.common.DeckRequestType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Deck requests. Plain SQL via JdbcTemplate; the names list is a jsonb array. */
@Repository
@RequiredArgsConstructor
public class DeckRequestRepository {

    private static final String SELECT = """
            select r.*, p.username as requester_username,
                   (select count(*) from cards c where c.deck_id = r.resulting_deck_id) as card_count
            from deck_requests r left join players p on p.id = r.requester_id""";
    private static final int LIST_LIMIT = 200;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    private RowMapper<DeckRequest> mapper() {
        return (rs, i) -> DeckRequest.builder()
                .id(rs.getObject("id", UUID.class))
                .type(DeckRequestType.get(rs.getInt("type")))
                .requesterId(rs.getObject("requester_id", UUID.class))
                .requesterUsername(rs.getString("requester_username"))
                .category(rs.getString("category"))
                .description(rs.getString("description"))
                .names(readNames(rs.getString("names")))
                .status(DeckRequestStatus.get(rs.getInt("status")))
                .adminNote(rs.getString("admin_note"))
                .resultingDeckId(rs.getObject("resulting_deck_id", UUID.class))
                .createdAt(instant(rs.getTimestamp("created_at")))
                .decidedAt(instant(rs.getTimestamp("decided_at")))
                .decidedBy(rs.getObject("decided_by", UUID.class))
                .notifiedAt(instant(rs.getTimestamp("notified_at")))
                .publishedAt(instant(rs.getTimestamp("published_at")))
                .cardCount(rs.getInt("card_count"))
                .build();
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private List<String> readNames(String jsonText) {
        try {
            return json.readValue(jsonText, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored names are not a JSON list", e);
        }
    }

    private String writeNames(List<String> names) {
        try {
            return json.writeValueAsString(names);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Serialises the "check the limits, then insert" of one requester (released at the end of the transaction). */
    public void lockRequester(UUID requesterId) {
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))::text", String.class, "deck-requests:" + requesterId);
    }

    public int countPending(UUID requesterId) {
        return jdbc.queryForObject("select count(*) from deck_requests where requester_id = ? and status = ?", Integer.class,
                requesterId, DeckRequestStatus.PENDING.dbVal());
    }

    public int countSince(UUID requesterId, Instant since) {
        return jdbc.queryForObject("select count(*) from deck_requests where requester_id = ? and created_at >= ?", Integer.class,
                requesterId, Timestamp.from(since));
    }

    public UUID insert(UUID requesterId, String category, String description, List<String> names, Instant now) {
        return jdbc.queryForObject("""
                insert into deck_requests (requester_id, category, description, names, created_at)
                values (?, ?, ?, ?::jsonb, ?) returning id""", UUID.class, requesterId, category, description, writeNames(names), Timestamp.from(now));
    }

    /** A request to make an existing deck global; the deck is {@code resulting_deck_id}, the names are a snapshot of its cards. */
    public UUID insertPromotion(UUID requesterId, UUID deckId, String category, String description, List<String> names, Instant now) {
        return jdbc.queryForObject("""
                insert into deck_requests (requester_id, category, description, names, created_at, type, resulting_deck_id)
                values (?, ?, ?, ?::jsonb, ?, ?, ?) returning id""", UUID.class, requesterId, category, description, writeNames(names), Timestamp.from(now),
                DeckRequestType.PROMOTE.dbVal(), deckId);
    }

    public boolean hasOpenPromotion(UUID deckId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from deck_requests where resulting_deck_id = ? and type = 1 and status = 0)", Boolean.class, deckId));
    }

    /** The latest promotion request of each of this owner's decks. */
    public List<DeckRequest> latestPromotionsOf(UUID ownerId) {
        return jdbc.query(SELECT + " where r.id in (select distinct on (resulting_deck_id) id from deck_requests where type = 1 and requester_id = ? order by resulting_deck_id, created_at desc)",
                mapper(), ownerId);
    }

    /** A deck is going away: its still-open promotion requests go with it. */
    public void deleteOpenPromotions(UUID deckId) {
        jdbc.update("delete from deck_requests where resulting_deck_id = ? and type = 1 and status = 0", deckId);
    }

    /** The requester withdraws a request that is still pending. */
    public boolean deletePending(UUID id, UUID requesterId) {
        return jdbc.update("delete from deck_requests where id = ? and requester_id = ? and status = 0", id, requesterId) == 1;
    }

    public Optional<DeckRequest> findById(UUID id) {
        return jdbc.query(SELECT + " where r.id = ?", mapper(), id).stream().findFirst();
    }

    public Optional<DeckRequest> findByIdForUpdate(UUID id) {
        return jdbc.query(SELECT + " where r.id = ? for update of r", mapper(), id).stream().findFirst();
    }

    public List<DeckRequest> findByRequester(UUID requesterId) {
        return jdbc.query(SELECT + " where r.requester_id = ? order by r.created_at desc limit " + LIST_LIMIT, mapper(), requesterId);
    }

    /** Pending first, then the rest, newest first inside each group. {@code status} null = every status. */
    public List<DeckRequest> findAll(DeckRequestStatus status) {
        String order = " order by (r.status = 0) desc, r.created_at desc limit " + LIST_LIMIT;
        return status == null ? jdbc.query(SELECT + order, mapper())
                : jdbc.query(SELECT + " where r.status = ?" + order, mapper(), status.dbVal());
    }

    public void updateContent(UUID id, String category, String description, List<String> names) {
        jdbc.update("update deck_requests set category = ?, description = ?, names = ?::jsonb where id = ?",
                category, description, writeNames(names), id);
    }

    public void markNotified(UUID id, Instant now) {
        jdbc.update("update deck_requests set notified_at = ? where id = ?", Timestamp.from(now), id);
    }

    public void markPublished(UUID id, Instant now) {
        jdbc.update("update deck_requests set published_at = ? where id = ?", Timestamp.from(now), id);
    }

    /** PENDING -> a decision; false when the request was no longer pending (someone decided first). */
    public boolean decide(UUID id, DeckRequestStatus status, String note, UUID decidedBy, UUID deckId, Instant now) {
        return jdbc.update("""
                update deck_requests set status = ?, admin_note = ?, decided_by = ?, resulting_deck_id = coalesce(?, resulting_deck_id), decided_at = ?
                where id = ? and status = ?""", status.dbVal(), note, decidedBy, deckId, Timestamp.from(now), id, DeckRequestStatus.PENDING.dbVal()) == 1;
    }
}
