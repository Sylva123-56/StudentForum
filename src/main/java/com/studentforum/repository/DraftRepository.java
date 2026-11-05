package com.studentforum.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DraftRepository {
    private final JdbcTemplate db;

    public DraftRepository(JdbcTemplate db) {
        this.db = db;
    }

    public List<Map<String, Object>> drafts(long user) {
        return db.queryForList("SELECT * FROM drafts WHERE user_id=? ORDER BY updated_at DESC LIMIT 100", user);
    }

    public int updateDraft(Long board, String type, String title, String content, String image, String cover, String attachment, String tags, LocalDateTime scheduled, long draftId, long user) {
        return db.update("UPDATE drafts SET board_id=?,type=?,title=?,content=?,image_path=?,cover_path=?,attachment_path=?,tag_ids=?,scheduled_at=? WHERE id=? AND user_id=?", board, type, title, content, image, cover, attachment, tags, scheduled, draftId, user);
    }

    public int deleteDraft(long draftId, long user) {
        return db.update("DELETE FROM drafts WHERE id=? AND user_id=?", draftId, user);
    }

    public Map<String, Object> ownedDraft(long draftId, long user) {
        return db.queryForList("SELECT * FROM drafts WHERE id=? AND user_id=?", draftId, user).stream().findFirst().orElse(null);
    }

    public Map<String, Object> draft(long draftId) {
        return db.queryForList("SELECT * FROM drafts WHERE id=?", draftId).stream().findFirst().orElse(null);
    }

    public Map<String, Object> ownedDraftId(long draftId, long user) {
        return db.queryForList("SELECT id FROM drafts WHERE id=? AND user_id=?", draftId, user).stream().findFirst().orElse(null);
    }

    public long insertDraft(long me) {
        org.springframework.jdbc.support.GeneratedKeyHolder key = new org.springframework.jdbc.support.GeneratedKeyHolder();
        db.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO drafts(user_id,content) VALUES(?,'')", java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, me);
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    public List<Map<String, Object>> dueDrafts() {
        return db.queryForList("SELECT * FROM drafts WHERE scheduled_at<=NOW() ORDER BY scheduled_at LIMIT 20");
    }

    public boolean claimDue(long draftId) {
        return db.update("UPDATE drafts SET scheduled_at=NULL WHERE id=? AND scheduled_at<=NOW()", draftId) == 1;
    }

    public void deletePublished(long draftId) {
        db.update("DELETE FROM drafts WHERE id=?", draftId);
    }
}
