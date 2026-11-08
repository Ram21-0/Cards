package com.cards.service.db;

import com.cards.common.DifficultyLevel;
import com.cards.common.Game;
import com.cards.common.GameStatus;
import com.cards.common.SeatInfo;
import com.cards.common.SeatKind;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tables: games, game_players. */
@Repository
@RequiredArgsConstructor
public class GameRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<Game> GAME = (rs, i) -> Game.builder()
            .id(rs.getObject("id", UUID.class))
            .joinCode(rs.getString("join_code"))
            .deckId(rs.getObject("deck_id", UUID.class))
            .hostId(rs.getObject("host_id", UUID.class))
            .maxPlayers(rs.getInt("max_players"))
            .turnTimeoutSeconds(rs.getObject("turn_timeout_seconds", Integer.class))
            .status(GameStatus.get(rs.getInt("status")))
            .rounds(rs.getObject("rounds", Integer.class))
            .createdAt(instant(rs, "created_at"))
            .startedAt(instant(rs, "started_at"))
            .finishedAt(instant(rs, "finished_at"))
            .build();

    private static final RowMapper<SeatInfo> SEAT = (rs, i) -> {
        Integer difficulty = rs.getObject("difficulty", Integer.class);
        return new SeatInfo(
                rs.getInt("seat"),
                SeatKind.get(rs.getInt("kind")),
                rs.getString("username"),
                difficulty == null ? null : DifficultyLevel.get(difficulty));
    };

    /**
     * Creates a WAITING game. Returns empty when the join code is already used. Done with ON CONFLICT instead of
     * catching a unique-violation, because a violation would abort the surrounding Postgres transaction.
     */
    public Optional<Game> insertIfCodeFree(String joinCode, UUID deckId, UUID hostId, int maxPlayers) {
        return jdbc.query("""
                        insert into games (join_code, deck_id, host_id, max_players) values (?, ?, ?, ?)
                        on conflict (join_code) do nothing returning *""",
                GAME, joinCode, deckId, hostId, maxPlayers).stream().findFirst();
    }

    public Optional<Game> findByJoinCode(String joinCode) {
        return jdbc.query("select * from games where join_code = ?", GAME, joinCode).stream().findFirst();
    }

    /** Same lookup, but locks the game row until the transaction ends (serializes concurrent joins). */
    public Optional<Game> findByJoinCodeForUpdate(String joinCode) {
        return jdbc.query("select * from games where join_code = ? for update", GAME, joinCode).stream().findFirst();
    }

    public boolean isSeated(UUID gameId, UUID playerId) {
        Integer n = jdbc.queryForObject("select count(*) from game_players where game_id = ? and player_id = ?",
                Integer.class, gameId, playerId);
        return n != null && n > 0;
    }

    /** Adds a human to a seat. */
    public void addHumanSeat(UUID gameId, int seat, UUID playerId) {
        jdbc.update("insert into game_players (game_id, seat, kind, player_id) values (?, ?, ?, ?)",
                gameId, seat, SeatKind.HUMAN.dbVal(), playerId);
    }

    /** Seats in order, with the username of human players. */
    public List<SeatInfo> findSeats(UUID gameId) {
        return jdbc.query("""
                select gp.seat, gp.kind, gp.difficulty, p.username
                from game_players gp left join players p on p.id = gp.player_id
                where gp.game_id = ? order by gp.seat""", SEAT, gameId);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
