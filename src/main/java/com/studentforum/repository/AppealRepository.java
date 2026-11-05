package com.studentforum.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AppealRepository {
    private final JdbcTemplate db;

    public AppealRepository(JdbcTemplate db) {
        this.db = db;
    }

    public List<Map<String, Object>> reputation(long user) {
        return db.queryForList("SELECT * FROM reputation_logs WHERE user_id=? ORDER BY id DESC LIMIT 100", user);
    }

    public List<Map<String, Object>> appealTargets(long user) {
        return db.queryForList("""
            SELECT candidates.target_type, candidates.target_id, candidates.label
            FROM (
                SELECT 'post' AS target_type, p.id AS target_id, p.title AS label
                FROM posts p
                WHERE p.author_id=? AND p.status IN ('hidden','deleted')
                  AND EXISTS (SELECT 1 FROM admin_logs l WHERE l.target_type='post' AND l.target_id=p.id AND l.action IN ('status','report_hide','report_delete'))
                UNION ALL
                SELECT 'reply', r.id, CONCAT('回复 · ', LEFT(r.content, 60))
                FROM replies r
                WHERE r.author_id=? AND r.status='deleted'
                  AND EXISTS (SELECT 1 FROM admin_logs l WHERE l.target_type='reply' AND l.target_id=r.id AND l.action='report_delete')
                UNION ALL
                SELECT 'user', u.id, CONCAT('我的账号 · ', u.username)
                FROM users u WHERE u.id=? AND u.status<>'active'
                UNION ALL
                SELECT 'report', rep.id, CONCAT('举报处理 · ', rep.target_type, ' #', rep.target_id, ' · ', rep.reason)
                FROM reports rep
                LEFT JOIN posts p ON rep.target_type='post' AND rep.target_id=p.id
                LEFT JOIN replies r ON rep.target_type='reply' AND rep.target_id=r.id
                WHERE rep.status='resolved' AND (rep.target_type='user' AND rep.target_id=? OR p.author_id=? OR r.author_id=?)
                UNION ALL
                SELECT 'message', m.id, CONCAT('私信 · ', DATE_FORMAT(m.created_at, '%Y-%m-%d %H:%i'))
                FROM messages m WHERE m.sender_id=? AND m.content='[已删除]'
            ) candidates
            WHERE NOT EXISTS (SELECT 1 FROM appeals a WHERE a.user_id=? AND a.target_type=candidates.target_type AND a.target_id=candidates.target_id AND a.status='pending')
            ORDER BY candidates.target_type, candidates.target_id DESC
            LIMIT 200
            """, user, user, user, user, user, user, user, user);
    }

    public int createAppeal(long user, String type, long target, String reason) {
        return db.update("INSERT INTO appeals(user_id,target_type,target_id,reason) VALUES(?,?,?,?)", user, type, target, reason);
    }

    public List<Map<String, Object>> appeals(long user) {
        return db.queryForList("SELECT * FROM appeals WHERE user_id=? ORDER BY id DESC LIMIT 100", user);
    }

    public int handleAppeal(String status, String note, long user, long appealId) {
        return db.update("UPDATE appeals SET status=?,handle_note=?,handler_id=?,handled_at=NOW() WHERE id=? AND status='pending'", status, note, user, appealId);
    }

    public int restoreAccount(long user) {
        return db.update("UPDATE users SET status='active' WHERE id=? AND status IN ('muted','banned')", user);
    }

    public Map<String, Object> postModeration(long target) {
        return db.queryForList("SELECT 1 FROM admin_logs WHERE target_type='post' AND target_id=? AND action IN ('status','report_hide','report_delete')", target).stream().findFirst().orElse(null);
    }

    public Map<String, Object> replyModeration(long target) {
        return db.queryForList("SELECT 1 FROM admin_logs WHERE target_type='reply' AND target_id=? AND action='report_delete'", target).stream().findFirst().orElse(null);
    }

    public Map<String, Object> messageForAppeal(long target) {
        return db.queryForList("SELECT sender_id,content FROM messages WHERE id=?", target).stream().findFirst().orElse(null);
    }

    public Map<String, Object> pendingAppeal(long user, String type, long target) {
        return db.queryForList("SELECT 1 FROM appeals WHERE user_id=? AND target_type=? AND target_id=? AND status='pending'", user, type, target).stream().findFirst().orElse(null);
    }

    public Map<String, Object> appeal(long appealId) {
        return db.queryForList("SELECT * FROM appeals WHERE id=?", appealId).stream().findFirst().orElse(null);
    }

    public List<Map<String, Object>> adminAppeals(String name, int offset) {
        String filter = name == null ? "" : " WHERE LOCATE(LOWER(?),LOWER(u.username))>0";
        String sql = "SELECT a.*,u.username FROM appeals a JOIN users u ON u.id=a.user_id" + filter + " ORDER BY (a.status='pending') DESC,a.id DESC LIMIT 21 OFFSET " + offset;
        return name == null ? db.queryForList(sql) : db.queryForList(sql, name);
    }

    public List<Map<String, Object>> adminReputation(String name, int offset) {
        String filter = name == null ? "" : " WHERE LOCATE(LOWER(?),LOWER(u.username))>0";
        String sql = "SELECT r.*,u.username FROM reputation_logs r JOIN users u ON u.id=r.user_id" + filter + " ORDER BY r.id DESC LIMIT 21 OFFSET " + offset;
        return name == null ? db.queryForList(sql) : db.queryForList(sql, name);
    }
}
