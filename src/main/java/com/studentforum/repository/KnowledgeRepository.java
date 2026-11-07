package com.studentforum.repository;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Shared detail projections and membership reads used by knowledge visibility policies. */
@Repository
public class KnowledgeRepository {
    private final JdbcTemplate db;

    public KnowledgeRepository(JdbcTemplate db) { this.db=db; }

    public Map<String,Object> resource(long id) {
        return db.queryForList("SELECT r.*,u.username FROM resources r JOIN users u ON u.id=r.uploader_id WHERE r.id=?",id).stream().findFirst().orElse(null);
    }

    public Map<String,Object> mistake(long id) {
        return db.queryForList("SELECT m.*,u.username FROM mistakes m JOIN users u ON u.id=m.user_id WHERE m.id=?",id).stream().findFirst().orElse(null);
    }

    public Map<String,Object> note(long id) {
        return db.queryForList("SELECT n.*,u.username FROM notes n JOIN users u ON u.id=n.user_id WHERE n.id=?",id).stream().findFirst().orElse(null);
    }

    public Map<String,Object> collection(long id) {
        return db.queryForList("SELECT c.*,u.username FROM collections c JOIN users u ON u.id=c.owner_id WHERE c.id=?",id).stream().findFirst().orElse(null);
    }

    public Map<String,Object> column(long id) {
        return db.queryForList("SELECT c.*,u.username FROM columns c JOIN users u ON u.id=c.owner_id WHERE c.id=?",id).stream().findFirst().orElse(null);
    }

    public Map<String,Object> publishedPost(long id) {
        return db.queryForList("SELECT id,title FROM posts WHERE id=? AND status='published'",id).stream().findFirst().orElse(null);
    }

    public Map<String,Object> activeMembership(long group, long user) {
        return db.queryForList("SELECT m.id FROM study_group_members m JOIN study_groups g ON g.id=m.group_id WHERE m.group_id=? AND m.user_id=? AND g.status='active'",group,user).stream().findFirst().orElse(null);
    }

    public void audit(long actor, String action, String type, long id, String detail) {
        db.update("INSERT INTO admin_logs(actor_id,action,target_type,target_id,detail) VALUES(?,?,?,?,?)",actor,action,type,id,detail);
    }
}
