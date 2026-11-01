package com.studentforum.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class GroupAdminRepository {
    private final JdbcTemplate db;

    public GroupAdminRepository(JdbcTemplate db) {
        this.db = db;
    }

    public List<Map<String, Object>> groups(String keyword) {
        return db.queryForList("SELECT g.*,u.username AS owner_username FROM study_groups g JOIN users u ON u.id=g.owner_id WHERE ?='' OR LOCATE(LOWER(?),LOWER(g.name))>0 OR LOCATE(LOWER(?),LOWER(g.description))>0 ORDER BY g.created_at DESC", keyword, keyword, keyword);
    }

    public List<Map<String, Object>> reports(String status) {
        return db.queryForList("SELECT r.*,g.name AS group_name,u.username AS reporter_username FROM study_group_reports r JOIN study_groups g ON g.id=r.group_id JOIN users u ON u.id=r.reporter_id WHERE r.status=? ORDER BY r.created_at DESC LIMIT 200", status);
    }

    public void handleReport(long reportId, long actorId, String status) {
        db.update("UPDATE study_group_reports SET status=?,handler_id=?,handled_at=NOW() WHERE id=?", status, actorId, reportId);
    }

    public boolean groupExists(long id) {
        return !db.queryForList("SELECT id FROM study_groups WHERE id=?", id).isEmpty();
    }

    public void updateStatus(long groupId, String status) {
        db.update("UPDATE study_groups SET status=? WHERE id=?", status, groupId);
    }

    public List<Map<String, Object>> logs(Long groupId) {
        if (groupId == null)
            return db.queryForList("SELECT l.*,u.username AS actor_username,g.name AS group_name FROM study_group_logs l LEFT JOIN users u ON u.id=l.actor_id JOIN study_groups g ON g.id=l.group_id ORDER BY l.created_at DESC LIMIT 200");
        return db.queryForList("SELECT l.*,u.username AS actor_username,g.name AS group_name FROM study_group_logs l LEFT JOIN users u ON u.id=l.actor_id JOIN study_groups g ON g.id=l.group_id WHERE l.group_id=? ORDER BY l.created_at DESC", groupId);
    }

    public void log(long groupId, long actor, String action, String type, long target, String detail) {
        db.update("INSERT INTO study_group_logs(group_id,actor_id,action,target_type,target_id,detail) VALUES(?,?,?,?,?,?)", groupId, actor, action, type, target, detail);
    }
}
