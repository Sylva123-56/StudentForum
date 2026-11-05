package com.studentforum.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FocusRepository {
    private final JdbcTemplate db;

    public FocusRepository(JdbcTemplate db) {
        this.db = db;
    }

    public List<Map<String, Object>> leaderboard(LocalDateTime start, int limit, int offset) {
        String sql = "SELECT f.user_id,u.username,u.points,"
                + "SUM(CASE WHEN f.started_at>=? THEN f.duration_minutes ELSE 0 END) AS range_minutes,"
                + "COUNT(CASE WHEN f.started_at>=? THEN 1 END) AS range_sessions,"
                + "SUM(f.duration_minutes) AS total_minutes "
                + "FROM focus_sessions f JOIN users u ON u.id=f.user_id WHERE u.status='active' "
                + "GROUP BY f.user_id,u.username,u.points ORDER BY range_minutes DESC,total_minutes DESC LIMIT ? OFFSET ?";
        return db.queryForList(sql, start, start, limit, offset);
    }

    public Map<String, Object> room(long roomId) {
        return db.queryForList("SELECT * FROM study_rooms WHERE id=?", roomId).stream().findFirst().orElse(null);
    }

    public boolean isMember(long roomId, long user) {
        return !db.queryForList("SELECT id FROM study_room_members WHERE room_id=? AND user_id=?", roomId, user).isEmpty();
    }

    public void join(long roomId, long user) {
        db.update("INSERT IGNORE INTO study_room_members(room_id,user_id,role,total_minutes) VALUES(?,?,'member',0)", roomId, user);
    }

    public Map<String, Object> plan(long planId, long user) {
        return db.queryForList("SELECT id FROM study_plans WHERE id=? AND user_id=?", planId, user).stream().findFirst().orElse(null);
    }

    public Map<String, Object> task(long taskId, long user) {
        return db.queryForList("SELECT t.id FROM study_tasks t JOIN study_plans p ON p.id=t.plan_id WHERE t.id=? AND p.user_id=?", taskId, user).stream().findFirst().orElse(null);
    }

    public Map<String, Object> taskTitle(long taskId) {
        return db.queryForList("SELECT title FROM study_tasks WHERE id=?", taskId).stream().findFirst().orElse(null);
    }

    public long insertSession(long user, String subject, String task, String note, int duration, LocalDateTime startedAt,
            LocalDateTime endedAt, Long roomId, Long planId, Long taskId, String source) {
        db.update("INSERT INTO focus_sessions(user_id,subject,task,note,duration_minutes,started_at,ended_at,room_id,plan_id,task_id,source) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                user, subject, task, note, duration, startedAt, endedAt, roomId, planId, taskId, source);
        return db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public void addMinutes(int duration, long roomId, long user) {
        db.update("UPDATE study_room_members SET total_minutes=total_minutes+?,last_active_at=NOW() WHERE room_id=? AND user_id=?", duration, roomId, user);
    }

    public Map<String, Object> session(long sessionId) {
        return db.queryForList("SELECT * FROM focus_sessions WHERE id=?", sessionId).stream().findFirst().orElse(null);
    }

    public List<Map<String, Object>> sessions(long user, LocalDateTime start, int offset) {
        return db.queryForList("SELECT f.*,r.name AS room_name,p.title AS plan_title FROM focus_sessions f LEFT JOIN study_rooms r ON r.id=f.room_id LEFT"
                + " JOIN study_plans p ON p.id=f.plan_id WHERE f.user_id=? AND f.started_at>=? ORDER BY f.started_at DESC LIMIT 21 OFFSET ?", user, start, offset);
    }

    public List<Map<String, Object>> daily(long user, LocalDateTime start) {
        return db.queryForList("SELECT DATE(started_at) AS date,COUNT(*) AS sessions,SUM(duration_minutes) AS minutes FROM focus_sessions WHERE user_id=?"
                + " AND started_at>=? GROUP BY DATE(started_at) ORDER BY date", user, start);
    }

    public List<Map<String, Object>> hourlyHeatmap(long user) {
        return db.queryForList("SELECT DAYOFWEEK(started_at) AS dow,HOUR(started_at) AS hour,SUM(duration_minutes) AS minutes FROM focus_sessions WHERE user_id=?"
                + " AND started_at>=DATE_SUB(CURRENT_DATE(),INTERVAL 56 DAY) GROUP BY DAYOFWEEK(started_at),HOUR(started_at)", user);
    }

    public List<Map<String, Object>> subjects(long user, LocalDateTime start) {
        return db.queryForList("SELECT subject,COUNT(*) AS sessions,SUM(duration_minutes) AS minutes FROM focus_sessions WHERE user_id=? AND started_at>=?"
                + " AND subject<>'' GROUP BY subject ORDER BY minutes DESC LIMIT 10", user, start);
    }

    public Map<String, Object> summary(long user, LocalDateTime start, LocalDateTime end) {
        return db.queryForList("SELECT COUNT(*) AS sessions,COALESCE(SUM(duration_minutes),0) AS minutes,COALESCE(MAX(duration_minutes),0) AS longest"
                + " FROM focus_sessions WHERE user_id=? AND started_at>=? AND started_at<?", user, start, end).stream().findFirst().orElse(null);
    }

    public Map<String, Object> todayMinutes(long user) {
        return db.queryForList("SELECT COALESCE(SUM(duration_minutes),0) AS minutes FROM focus_sessions WHERE user_id=? AND DATE(started_at)=CURRENT_DATE()", user).stream().findFirst().orElse(null);
    }

    public Map<String, Object> weekMinutes(long user) {
        return db.queryForList("SELECT COALESCE(SUM(duration_minutes),0) AS minutes FROM focus_sessions WHERE user_id=? AND YEARWEEK(started_at,1)=YEARWEEK(CURRENT_DATE(),1)", user).stream().findFirst().orElse(null);
    }

    public Map<String, Object> monthMinutes(long user) {
        return db.queryForList("SELECT COALESCE(SUM(duration_minutes),0) AS minutes FROM focus_sessions WHERE user_id=? AND DATE_FORMAT(started_at,'%Y-%m')=DATE_FORMAT(CURRENT_DATE(),'%Y-%m')", user).stream().findFirst().orElse(null);
    }

    public Map<String, Object> totalMinutes(long user) {
        return db.queryForList("SELECT COALESCE(SUM(duration_minutes),0) AS minutes FROM focus_sessions WHERE user_id=?", user).stream().findFirst().orElse(null);
    }

    public Map<String, Object> myLeaderboard(long me, LocalDateTime start) {
        return db.queryForList("SELECT SUM(duration_minutes) AS minutes,COUNT(*) AS sessions FROM focus_sessions WHERE user_id=? AND started_at>=?", me, start).stream().findFirst().orElse(null);
    }

    public List<Map<String, Object>> participatedRooms(long user) {
        return db.queryForList("SELECT r.id,r.name,r.status,r.owner_id,r.focus_minutes,r.created_at,m.total_minutes,m.joined_at FROM study_room_members m"
                + " JOIN study_rooms r ON r.id=m.room_id WHERE m.user_id=? ORDER BY r.created_at DESC LIMIT 20", user);
    }

    public List<Map<String, Object>> ownedRooms(long user) {
        return db.queryForList("SELECT id,name,status,member_count,focus_minutes,created_at FROM study_rooms WHERE owner_id=? ORDER BY created_at DESC LIMIT 20", user);
    }
}
