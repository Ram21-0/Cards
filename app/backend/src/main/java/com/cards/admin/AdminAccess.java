package com.cards.admin;

import com.cards.common.Player;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Who is an admin: a player whose stored Google email (only ever stored from a token with email_verified = true) is in the
 * allow-list {@code ADMIN_EMAILS}. Decided on every request and never stored, so removing an address from the environment
 * revokes the access. With dev login enabled (local development only) the fixed dev admin address also counts.
 */
@Component
public class AdminAccess {

    /** The email the local dev login gives to {@code ?admin=true}; only an admin while app.dev-login.enabled is true. */
    public static final String DEV_ADMIN_EMAIL = "dev-admin@dev.invalid";

    private final Set<String> emails;
    private final boolean devLoginEnabled;

    public AdminAccess(@Value("${app.admin.emails:}") String allowList, @Value("${app.dev-login.enabled:false}") boolean devLoginEnabled) {
        this.emails = parse(allowList);
        this.devLoginEnabled = devLoginEnabled;
    }

    public boolean isAdmin(Player player) {
        if (player == null || player.email() == null) return false;
        String email = player.email().trim().toLowerCase(Locale.ROOT);
        if (email.isEmpty()) return false;
        return emails.contains(email) || (devLoginEnabled && email.equals(DEV_ADMIN_EMAIL));
    }

    static Set<String> parse(String list) {
        return Arrays.stream(list == null ? new String[0] : list.split("[,;\\s]+"))
                .map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }
}
