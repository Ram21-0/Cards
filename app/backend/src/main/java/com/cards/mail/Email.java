package com.cards.mail;

import java.util.List;

/** An outgoing message. {@code html} and {@code text} are the two bodies; the caller must have HTML-escaped anything user-supplied in {@code html}. */
public record Email(List<String> to, String subject, String html, String text) {
}
