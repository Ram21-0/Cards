package com.cards.service.service;

import com.cards.common.BadRequestException;

import java.util.Locale;
import java.util.regex.Pattern;

/** The one place that defines what a valid username is: trimmed, lowercased, 1-8 chars of [a-z0-9_]. */
public final class Usernames {

    private static final Pattern VALID = Pattern.compile("^[a-z0-9_]{1,8}$");

    private Usernames() {
    }

    /** Returns the normalized username, or throws {@link BadRequestException}. */
    public static String normalize(String raw) {
        String username = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!VALID.matcher(username).matches()) {
            throw new BadRequestException("Username must be 1-8 characters: letters, digits or underscore");
        }
        return username;
    }
}
