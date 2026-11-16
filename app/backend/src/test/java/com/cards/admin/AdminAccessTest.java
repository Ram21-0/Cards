package com.cards.admin;

import com.cards.common.Player;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminAccessTest {

    private static Player withEmail(String email) {
        return Player.builder().id(UUID.randomUUID()).username("x").email(email).build();
    }

    @Test
    void anEmailInTheAllowListIsAnAdminIgnoringCaseAndSpaces() {
        AdminAccess access = new AdminAccess(" Boss@Example.com , second@example.org;third@example.org ", false);
        assertTrue(access.isAdmin(withEmail("boss@example.com")));
        assertTrue(access.isAdmin(withEmail("BOSS@EXAMPLE.COM")));
        assertTrue(access.isAdmin(withEmail(" second@example.org ")));
        assertTrue(access.isAdmin(withEmail("third@example.org")));
        assertFalse(access.isAdmin(withEmail("other@example.com")));
        assertFalse(access.isAdmin(withEmail("boss@example.com.evil.net")));
    }

    @Test
    void nobodyIsAnAdminWithoutAnAllowListOrWithoutAnEmail() {
        assertFalse(new AdminAccess("", false).isAdmin(withEmail("boss@example.com")));
        assertFalse(new AdminAccess(null, false).isAdmin(withEmail("boss@example.com")));
        AdminAccess access = new AdminAccess("boss@example.com", false);
        assertFalse(access.isAdmin(withEmail(null)));
        assertFalse(access.isAdmin(withEmail("  ")));
        assertFalse(access.isAdmin(null));
    }

    @Test
    void theDevAdminEmailCountsOnlyWhileDevLoginIsEnabled() {
        assertTrue(new AdminAccess("", true).isAdmin(withEmail(AdminAccess.DEV_ADMIN_EMAIL)));
        assertFalse(new AdminAccess("", false).isAdmin(withEmail(AdminAccess.DEV_ADMIN_EMAIL)), "never in production");
        assertFalse(new AdminAccess("boss@example.com", true).isAdmin(withEmail("someone@dev.invalid")));
    }
}
