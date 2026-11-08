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

    private static final RowMapper<Player> PLAYER = (rs, i) -> new Player(
            rs.getObject("id", UUID.class),
            rs.getString("username"),
            rs.getTimestamp("created_at").toInstant());

    /** Inserts the player; empty when the username is already taken (no exception, safe inside a transaction). */
    public Optional<Player> insertIfAbsent(String username) {
        return jdbc.query("insert into players (username) values (?) on conflict (username) do nothing returning *",
                PLAYER, username).stream().findFirst();
    }

    public Optional<Player> findById(UUID id) {
        return jdbc.query("select * from players where id = ?", PLAYER, id).stream().findFirst();
    }
}
