package com.cards.service.service;

import com.cards.common.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UsernamesTest {

    @Test
    void trimsAndLowercases() {
        assertEquals("ann_1", Usernames.normalize("  Ann_1 "));
    }

    @Test
    void acceptsTheBoundaries() {
        assertEquals("a", Usernames.normalize("a"));
        assertEquals("abcdefgh", Usernames.normalize("abcdefgh"));
        assertEquals("_0_9_z_a", Usernames.normalize("_0_9_Z_A"));
    }

    @Test
    void rejectsEverythingElse() {
        for (String bad : new String[]{null, "", "   ", "abcdefghi", "a b", "a-b", "a.b", "a@b", "ünï", "名前", "a\nb", "<b>"}) {
            assertThrows(BadRequestException.class, () -> Usernames.normalize(bad), "should reject: " + bad);
        }
    }
}
