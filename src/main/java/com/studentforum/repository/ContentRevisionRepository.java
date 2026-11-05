package com.studentforum.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ContentRevisionRepository {
    private final JdbcTemplate db;

    public ContentRevisionRepository(JdbcTemplate db) {
        this.db = db;
    }

    public List<Map<String, Object>> postRevisions(long postId) {
        return db.queryForList("SELECT id,title,created_at FROM post_revisions WHERE post_id=? ORDER BY id DESC", postId);
    }

    public List<Map<String, Object>> replyRevisions(long replyId) {
        return db.queryForList("SELECT * FROM reply_revisions WHERE reply_id=? ORDER BY id DESC", replyId);
    }

    public int recordReplyRevision(long replyId, long user, Object content) {
        return db.update("INSERT INTO reply_revisions(reply_id,editor_id,content) VALUES(?,?,?)", replyId, user, content);
    }

    public int updateReply(String content, long replyId) {
        return db.update("UPDATE replies SET content=? WHERE id=?", content, replyId);
    }

    public Map<String, Object> postRevision(long postId, long revisionId) {
        return db.queryForList("SELECT * FROM post_revisions WHERE post_id=? AND id=?", postId, revisionId).stream().findFirst().orElse(null);
    }
}
