package com.cards.service.db;

import com.cards.common.Player;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class PlayerRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<Player> PLAYER = (rs, i) -> Player.builder()
            .id(rs.getObject("id", UUID.class))
            .username(rs.getString("username"))
            .googleSub(rs.getString("google_sub"))
            .email(rs.getString("email"))
            .displayName(rs.getString("display_name"))
            .avatarUrl(rs.getString("avatar_url"))
            .createdAt(rs.getTimestamp("created_at").toInstant())
            .build();

    /** Inserts the player; empty when the username is already taken (no exception, safe inside a transaction). */
    public Optional<Player> insertIfAbsent(String username) {
        return jdbc.query("insert into players (username) values (?) on conflict (username) do nothing returning *",
                PLAYER, username).stream().findFirst();
    }

    public Optional<Player> findById(UUID id) {
        return jdbc.query("select * from players where id = ?", PLAYER, id).stream().findFirst();
    }

    public Optional<Player> findByUsername(String username) {
        return jdbc.query("select * from players where username = ?", PLAYER, username).stream().findFirst();
    }

    /** Google sign-in: create the account on first login, refresh email/name/avatar on later ones. Keyed by {@code sub}. */
    public Player upsertGoogle(String sub, String email, String displayName, String avatarUrl) {
        return jdbc.query("""
                        insert into players (google_sub, email, display_name, avatar_url) values (?, ?, ?, ?)
                        on conflict (google_sub) do update
                          set email = excluded.email, display_name = excluded.display_name, avatar_url = excluded.avatar_url
                        returning *""",
                PLAYER, sub, email, displayName, avatarUrl).get(0);
    }

    /** Development login only: an account with a fake "dev:" sub. Empty when the username is taken. */
    public Optional<Player> insertDev(String username) {
        return jdbc.query("""
                        insert into players (username, google_sub, display_name) values (?, ?, ?)
                        on conflict (username) do nothing returning *""",
                PLAYER, username, "dev:" + username, username).stream().findFirst();
    }

    /** Sets the username unless someone else has it. Empty = taken. */
    public Optional<Player> updateUsername(UUID id, String username) {
        return jdbc.query("""
                        update players set username = ?
                        where id = ? and not exists (select 1 from players o where o.username = ? and o.id <> ?)
                        returning *""",
                PLAYER, username, id, username, id).stream().findFirst();
    }

    /**
     * Account deletion: remove everything personal but keep the row, so finished games keep their history.
     * The username is freed for others.
     */
    public void anonymize(UUID id) {
        jdbc.update("update players set username = null, google_sub = null, email = null, display_name = null, avatar_url = null where id = ?", id);
    }
}
