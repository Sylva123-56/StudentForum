package com.studentforum.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MentionRepository {
    private final JdbcTemplate db;

    public MentionRepository(JdbcTemplate db) {
        this.db = db;
    }

    public List<Map<String, Object>> users(String username) {
        return db.queryForList("SELECT id,notify_mention FROM users WHERE username=?", username);
    }

    public boolean messageParticipant(long messageId, long recipient) {
        return !db.queryForList("SELECT 1 FROM messages m JOIN conversations c ON c.id=m.conversation_id WHERE m.id=? AND (c.user_a_id=? OR c.user_b_id=?)", messageId, recipient, recipient).isEmpty();
    }

    public boolean insert(long user, long actor, String type, long target) {
        return db.update("INSERT IGNORE INTO mentions(user_id,actor_id,target_type,target_id) VALUES(?,?,?,?)", user, actor, type, target) > 0;
    }
}
