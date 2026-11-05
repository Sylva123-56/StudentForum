package com.studentforum.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MistakeImageRepository {
    private final JdbcTemplate db;

    public MistakeImageRepository(JdbcTemplate db) {
        this.db = db;
    }

    public long insertImage(long user, String stored, String mime) {
        var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
        db.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO mistake_images(user_id,stored_name,mime_type) VALUES(?,?,?)", java.sql.Statement.RETURN_GENERATED_KEYS);
            Object[] fields = {user, stored, mime};
            for (int i = 0; i < fields.length; i++) statement.setObject(i + 1, fields[i]);
            return statement;
        }, key);
        return java.util.Objects.requireNonNull(key.getKey()).longValue();
    }

    public Map<String, Object> ownedImage(long imageId, long user) {
        return db.queryForList("SELECT * FROM mistake_images WHERE id=? AND user_id=?", imageId, user).stream().findFirst().orElse(null);
    }
}
