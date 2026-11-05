package com.studentforum.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BadgeRepository {
    private final JdbcTemplate db;

    public BadgeRepository(JdbcTemplate db) {
        this.db = db;
    }

    public List<Map<String, Object>> goals(long user) {
        return db.queryForList("SELECT id FROM checkin_goals WHERE user_id=?", user);
    }

    public List<Map<String, Object>> checkinDates(long goal, long user) {
        return db.queryForList("SELECT checkin_date FROM checkins WHERE goal_id=? AND user_id=? ORDER BY checkin_date DESC LIMIT 400", goal, user);
    }

    public List<Map<String, Object>> owned(long user) {
        return db.queryForList("SELECT code,earned_at FROM user_badges WHERE user_id=?", user);
    }

    public boolean insert(long user, String code, String name, String detail) {
        return db.update("INSERT IGNORE INTO user_badges(user_id,code,name,detail) VALUES(?,?,?,?)", user, code, name, detail) > 0;
    }

    public long checkins(long user) {
        Long value = db.queryForObject("SELECT COUNT(*) FROM checkins WHERE user_id=?", Long.class, user);
        return value == null ? 0 : value;
    }

    public long focusMinutes(long user) {
        Long value = db.queryForObject("SELECT COALESCE(SUM(duration_minutes),0) FROM focus_sessions WHERE user_id=?", Long.class, user);
        return value == null ? 0 : value;
    }

    public long focusSessions(long user) {
        Long value = db.queryForObject("SELECT COUNT(*) FROM focus_sessions WHERE user_id=?", Long.class, user);
        return value == null ? 0 : value;
    }

    public long plans(long user) {
        Long value = db.queryForObject("SELECT COUNT(*) FROM study_plans WHERE user_id=?", Long.class, user);
        return value == null ? 0 : value;
    }

    public long completedPlans(long user) {
        Long value = db.queryForObject("SELECT COUNT(*) FROM study_plans WHERE user_id=? AND status='completed'", Long.class, user);
        return value == null ? 0 : value;
    }

    public long hostedRooms(long user) {
        Long value = db.queryForObject("SELECT COUNT(*) FROM study_rooms WHERE owner_id=?", Long.class, user);
        return value == null ? 0 : value;
    }
}
