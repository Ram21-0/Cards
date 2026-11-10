package com.cards.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameSettingsTest {

    @Test
    void theDefaultsAreTheAgreedOnes() {
        GameSettings d = GameSettings.defaults();
        assertEquals(500, d.maxRounds());
        assertEquals(3, d.chooseThreshold());
        assertEquals(5, d.clashCallSeconds());
        assertNull(d.turnTimeoutSeconds(), "no turn limit by default");
    }

    @Test
    void theBoundariesAreAccepted() {
        new GameSettings(20, 0, 2, 5);
        new GameSettings(5000, 10, 30, 120);
        new GameSettings(20, 10, 2, null);
    }

    @Test
    void valuesOutsideTheRangeAreRejectedWithAMessageNamingTheSetting() {
        assertEquals("maxRounds must be between 20 and 5000", assertThrows(IllegalArgumentException.class, () -> new GameSettings(19, 3, 5, null)).getMessage());
        assertEquals("maxRounds must be between 20 and 5000", assertThrows(IllegalArgumentException.class, () -> new GameSettings(5001, 3, 5, null)).getMessage());
        assertEquals("chooseThreshold must be between 0 and 10", assertThrows(IllegalArgumentException.class, () -> new GameSettings(500, -1, 5, null)).getMessage());
        assertEquals("chooseThreshold must be between 0 and 10", assertThrows(IllegalArgumentException.class, () -> new GameSettings(500, 11, 5, null)).getMessage());
        assertEquals("clashCallSeconds must be between 2 and 30", assertThrows(IllegalArgumentException.class, () -> new GameSettings(500, 3, 1, null)).getMessage());
        assertEquals("clashCallSeconds must be between 2 and 30", assertThrows(IllegalArgumentException.class, () -> new GameSettings(500, 3, 31, null)).getMessage());
        assertEquals("turnTimeoutSeconds must be between 5 and 120", assertThrows(IllegalArgumentException.class, () -> new GameSettings(500, 3, 5, 4)).getMessage());
        assertEquals("turnTimeoutSeconds must be between 5 and 120", assertThrows(IllegalArgumentException.class, () -> new GameSettings(500, 3, 5, 121)).getMessage());
    }

    @Test
    void theHostsOverridesWinAndMissingOnesKeepTheDefault() {
        GameSettings defaults = new GameSettings(300, 2, 8, 30);
        GameSettings s = GameSettings.resolve(100, null, null, null, defaults);
        assertEquals(new GameSettings(100, 2, 8, 30), s);
        assertEquals(defaults, GameSettings.resolve(null, null, null, null, defaults));
        assertEquals(new GameSettings(20, 0, 2, 5), GameSettings.resolve(20, 0, 2, 5, defaults), "0 is a real override, not 'missing'");
    }

    @Test
    void anOverrideIsValidatedToo() {
        assertThrows(IllegalArgumentException.class, () -> GameSettings.resolve(10, null, null, null, GameSettings.defaults()));
    }

    @Test
    void chooseNeedsAtLeastTwoCardsAndAtMostTheThreshold() {
        GameSettings s = new GameSettings(500, 3, 5, null);
        assertFalse(s.mayChoose(0));
        assertFalse(s.mayChoose(1));
        assertTrue(s.mayChoose(2));
        assertTrue(s.mayChoose(3));
        assertFalse(s.mayChoose(4));
        assertFalse(new GameSettings(500, 0, 5, null).mayChoose(2));
        assertFalse(new GameSettings(500, 1, 5, null).mayChoose(1));
    }
}
