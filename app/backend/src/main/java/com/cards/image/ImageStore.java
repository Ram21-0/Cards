package com.cards.image;

import java.util.Optional;
import java.util.UUID;

/** Where processed card images live. The default keeps them in Postgres; an S3/R2 implementation can replace it without touching the rest. */
public interface ImageStore {

    /** Keeps the image and returns its id; {@link #urlOf} is how cards refer to it. */
    UUID store(UUID ownerId, byte[] bytes, String contentType, int width, int height);

    Optional<StoredImage> load(UUID id);

    /** Deletes these images unless some card still points at them. */
    void deleteUnused(java.util.Collection<UUID> ids);

    /** The address cards store and the frontend uses. */
    static String urlOf(UUID id) {
        return "/api/images/" + id;
    }

    /** The id inside an address made by {@link #urlOf}, or null for anything else (for example an external https URL). */
    static UUID idOf(String url) {
        if (url == null || !url.startsWith("/api/images/")) return null;
        try {
            return UUID.fromString(url.substring("/api/images/".length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    record StoredImage(String contentType, byte[] bytes) {
    }
}
