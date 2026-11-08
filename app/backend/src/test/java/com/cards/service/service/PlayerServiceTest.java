package com.cards.service.service;

import com.cards.common.BadRequestException;
import com.cards.common.ConflictException;
import com.cards.common.Player;
import com.cards.service.db.PlayerRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerServiceTest {

    private final PlayerRepository repo = mock(PlayerRepository.class);
    private final PlayerService service = new PlayerService(repo);

    private static Player player(String name) {
        return new Player(UUID.randomUUID(), name, Instant.now());
    }

    @Test
    void trimsAndLowercasesBeforeSaving() {
        when(repo.insertIfAbsent("ann_1")).thenReturn(Optional.of(player("ann_1")));
        assertEquals("ann_1", service.create("  Ann_1 ").username());
        verify(repo).insertIfAbsent("ann_1");
    }

    @Test
    void acceptsOneToEightCharacters() {
        when(repo.insertIfAbsent(anyString())).thenAnswer(i -> Optional.of(player(i.getArgument(0))));
        service.create("a");
        service.create("abcdefgh");
    }

    @Test
    void rejectsInvalidUsernamesWithoutTouchingTheDatabase() {
        for (String bad : new String[]{null, "", "   ", "abcdefghi", "an n", "ann!", "änn", "a-b", "a.b"}) {
            assertThrows(BadRequestException.class, () -> service.create(bad), "should reject: " + bad);
        }
        verify(repo, never()).insertIfAbsent(anyString());
    }

    @Test
    void takenUsernameIsAConflict() {
        when(repo.insertIfAbsent("ann")).thenReturn(Optional.empty());
        ConflictException e = assertThrows(ConflictException.class, () -> service.create("ann"));
        assertEquals("Username already taken", e.getMessage());
    }
}
