package com.cards.image;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/** The images table (bytea). Fine for a small deck-sharing site; move to object storage behind {@link ImageStore} when it grows. */
@Repository
@RequiredArgsConstructor
public class PostgresImageStore implements ImageStore {

    private final JdbcTemplate jdbc;

    @Override
    public UUID store(UUID ownerId, byte[] bytes, String contentType, int width, int height) {
        return jdbc.queryForObject("insert into images (owner_id, content_type, data, width, height) values (?, ?, ?, ?, ?) returning id",
                UUID.class, ownerId, contentType, bytes, width, height);
    }

    @Override
    public Optional<StoredImage> load(UUID id) {
        return jdbc.query("select content_type, data from images where id = ?", (rs, i) -> new StoredImage(rs.getString(1), rs.getBytes(2)), id).stream().findFirst();
    }

    @Override
    public void deleteUnused(Collection<UUID> ids) {
        for (UUID id : ids) {
            jdbc.update("delete from images where id = ? and not exists (select 1 from cards c where c.img_url = ?)", id, ImageStore.urlOf(id));
        }
    }
}
