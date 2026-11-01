package com.studentforum.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class GroupRepository {
    private final JdbcTemplate db;

    public GroupRepository(JdbcTemplate db) {
        this.db = db;
    }

    public List<Map<String, Object>> rankings(String type) {
        String order = "members".equals(type) ? "member_count DESC" : "subject".equals(type) ? "subject,activity_score DESC" : "activity_score DESC";
        return db.queryForList("SELECT g.id,g.name,g.subject,g.member_count,g.post_count,g.activity_score,u.username AS owner_username FROM study_groups g JOIN users u ON u.id=g.owner_id WHERE g.status='active' ORDER BY " + order + ",g.created_at DESC LIMIT 20");
    }

    public List<Map<String, Object>> recommended(long user, String subject) {
        return db.queryForList("SELECT g.*,u.username AS owner_username FROM study_groups g JOIN users u ON u.id=g.owner_id WHERE g.status='active' AND g.visibility='public' AND g.id NOT IN (SELECT group_id FROM study_group_members WHERE user_id=?) ORDER BY (CASE WHEN ?<>'' AND g.subject=? THEN 0 ELSE 1 END),g.activity_score DESC,g.created_at DESC LIMIT 12", user, subject, subject);
    }

    public List<Map<String, Object>> search(String keyword,
                                          String tag,
                                          String subject,
                                          String school,
                                          String status,
                                          String sort) {
        StringBuilder sql = new StringBuilder("SELECT g.*,u.username AS owner_username FROM study_groups g JOIN users u ON u.id=g.owner_id WHERE g.status=?");
        List<Object> args = new ArrayList<>(List.of(status));
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (LOCATE(LOWER(?),LOWER(g.name))>0 OR LOCATE(LOWER(?),LOWER(g.description))>0 OR LOCATE(LOWER(?),LOWER(g.tags))>0)");
            String q = keyword.trim();
            args.add(q);
            args.add(q);
            args.add(q);
        }
        if (tag != null && !tag.isBlank()) {
            sql.append(" AND LOCATE(LOWER(?),LOWER(g.tags))>0");
            args.add(tag.trim());
        }
        if (subject != null && !subject.isBlank()) {
            sql.append(" AND g.subject=?");
            args.add(subject.trim());
        }
        if (school != null && !school.isBlank()) {
            sql.append(" AND g.school=?");
            args.add(school.trim());
        }
        sql.append(" ORDER BY ").append("members".equals(sort) ? "g.member_count DESC," : "activity".equals(sort) ? "g.activity_score DESC," : "g.created_at DESC,").append(" g.created_at DESC LIMIT 100");
        return db.queryForList(sql.toString(), args.toArray());
    }

}
