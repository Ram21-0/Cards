package com.cards.service.controller;

import com.cards.image.ImageStore;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

/**
 * Serves an uploaded card image by its unguessable id. Public (a browser's img tag cannot send our session) and cached for a year: the id changes
 * whenever the picture does. The content type is always the one WE wrote after re-encoding (JPEG or PNG), and nosniff is set.
 */
@RestController
@RequestMapping("/api/images")
@RequiredArgsConstructor
public class ImageController {

    private final ImageStore images;

    @GetMapping("/{id}")
    public ResponseEntity<byte[]> get(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        String etag = "\"" + id + "\"";
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(304).eTag(etag).cacheControl(cache()).build();
        }
        return images.load(id)
                .map(img -> ResponseEntity.ok().contentType(MediaType.parseMediaType(img.contentType())).eTag(etag).cacheControl(cache())
                        .header("X-Content-Type-Options", "nosniff").body(img.bytes()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static CacheControl cache() {
        return CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();
    }
}
