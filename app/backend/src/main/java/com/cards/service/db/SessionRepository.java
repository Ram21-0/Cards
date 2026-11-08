package com.cards.service.db;

import com.cards.common.SessionRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Table: sessions (keyed by the SHA-256 hash of the token). */
@Repository
@RequiredArgsConstructor
public class SessionRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<SessionRecord> SESSION = (rs, i) -> new SessionRecord(
            rs.getString("token_hash"),
            rs.getObject("player_id", UUID.class),
            rs.getTimestamp("expires_at").toInstant(),
            rs.getTimestamp("last_seen").toInstant());

    public void insert(String tokenHash, UUID playerId, Instant expiresAt) {
        jdbc.update("insert into sessions (token_hash, player_id, expires_at) values (?, ?, ?)",
                tokenHash, playerId, Timestamp.from(expiresAt));
    }

    public Optional<SessionRecord> find(String tokenHash) {
        return jdbc.query("select * from sessions where token_hash = ?", SESSION, tokenHash).stream().findFirst();
    }

    public void touch(String tokenHash, Instant now) {
        jdbc.update("update sessions set last_seen = ? where token_hash = ?", Timestamp.from(now), tokenHash);
    }

    public void delete(String tokenHash) {
        jdbc.update("delete from sessions where token_hash = ?", tokenHash);
    }

    public void deleteAllFor(UUID playerId) {
        jdbc.update("delete from sessions where player_id = ?", playerId);
    }

    public void deleteExpired(Instant now) {
        jdbc.update("delete from sessions where expires_at < ?", Timestamp.from(now));
    }
}
