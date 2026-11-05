package com.studentforum.repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminStudyRepository {
    private final JdbcTemplate db;

    public AdminStudyRepository(JdbcTemplate db) {
        this.db = db;
    }

    public void log(long actor, String action, String targetType, long targetId, String detail) {
        db.update("INSERT INTO admin_logs(actor_id,action,target_type,target_id,detail) VALUES(?,?,?,?,?)", actor, action, targetType, targetId, detail);
    }

    public Map<String, Object> todayCheckins() {
        return db.queryForList("SELECT COUNT(*) AS checkins,COUNT(DISTINCT user_id) AS users,COALESCE(SUM(duration_minutes),0) AS minutes,COALESCE(SUM(points),0) AS points FROM checkins WHERE checkin_date=CURRENT_DATE()").stream().findFirst().orElse(null);
    }

    public Map<String, Object> totalCheckins() {
        return db.queryForList("SELECT COUNT(*) AS checkins,COUNT(DISTINCT user_id) AS users,COALESCE(SUM(duration_minutes),0) AS minutes,COALESCE(SUM(points),0) AS points FROM checkins").stream().findFirst().orElse(null);
    }

    public Map<String, Object> recentMakeups() {
        return db.queryForList("SELECT COUNT(*) AS count FROM checkins WHERE make_up=TRUE AND checkin_date>=DATE_SUB(CURRENT_DATE(),INTERVAL 30 DAY)").stream().findFirst().orElse(null);
    }

    public Map<String, Object> goalSummary() {
        return db.queryForList("SELECT COUNT(*) AS total,COALESCE(SUM(status='active'),0) AS active,COALESCE(SUM(visibility='group'),0) AS group_goals FROM checkin_goals").stream().findFirst().orElse(null);
    }

    public List<Map<String, Object>> checkinTrend() {
        return db.queryForList("SELECT checkin_date AS date,COUNT(*) AS count,COUNT(DISTINCT user_id) AS users FROM checkins WHERE checkin_date>=DATE_SUB(CURRENT_DATE(),INTERVAL 13 DAY) GROUP BY checkin_date ORDER BY checkin_date");
    }

    public List<Map<String, Object>> checkinSubjects() {
        return db.queryForList("SELECT subject,COUNT(*) AS count,SUM(duration_minutes) AS minutes FROM checkins WHERE subject<>'' AND checkin_date>=DATE_SUB(CURRENT_DATE(),INTERVAL 30 DAY) GROUP BY subject ORDER BY count DESC LIMIT 10");
    }

    public List<Map<String, Object>> badges() {
        return db.queryForList("SELECT code,name,COUNT(*) AS count FROM user_badges GROUP BY code,name ORDER BY count DESC LIMIT 20");
    }

    public List<Map<String, Object>> recentCheckins() {
        return db.queryForList("SELECT c.id,c.user_id,u.username,c.goal_id,g.title AS goal_title,c.content,c.subject,c.duration_minutes,c.make_up,c.checkin_date,c.points,c.created_at FROM checkins c JOIN users u ON u.id=c.user_id LEFT JOIN checkin_goals g ON g.id=c.goal_id ORDER BY c.created_at DESC LIMIT 30");
    }

    public Map<String, Object> focusSummary(LocalDateTime start) {
        return db.queryForList("SELECT COUNT(*) AS sessions,COUNT(DISTINCT user_id) AS users,COALESCE(SUM(duration_minutes),0) AS minutes,COALESCE(AVG(duration_minutes),0) AS average FROM focus_sessions WHERE started_at>=?", start).stream().findFirst().orElse(null);
    }

    public Map<String, Object> todayFocus() {
        return db.queryForList("SELECT COUNT(*) AS sessions,COUNT(DISTINCT user_id) AS users,COALESCE(SUM(duration_minutes),0) AS minutes FROM focus_sessions WHERE DATE(started_at)=CURRENT_DATE()").stream().findFirst().orElse(null);
    }

    public List<Map<String, Object>> focusTrend(LocalDateTime start) {
        return db.queryForList("SELECT DATE(started_at) AS date,COUNT(*) AS sessions,COUNT(DISTINCT user_id) AS users,SUM(duration_minutes) AS minutes FROM focus_sessions WHERE started_at>=? GROUP BY DATE(started_at) ORDER BY date", start);
    }

    public List<Map<String, Object>> focusSubjects(LocalDateTime start) {
        return db.queryForList("SELECT subject,COUNT(*) AS sessions,SUM(duration_minutes) AS minutes FROM focus_sessions WHERE subject<>'' AND started_at>=? GROUP BY subject ORDER BY minutes DESC LIMIT 10", start);
    }

    public List<Map<String, Object>> focusSources(LocalDateTime start) {
        return db.queryForList("SELECT source,COUNT(*) AS sessions,SUM(duration_minutes) AS minutes FROM focus_sessions WHERE started_at>=? GROUP BY source ORDER BY sessions DESC", start);
    }

    public List<Map<String, Object>> focusLeaders(LocalDateTime start) {
        return db.queryForList("SELECT f.user_id,u.username,COUNT(*) AS sessions,SUM(f.duration_minutes) AS minutes FROM focus_sessions f JOIN users u ON u.id=f.user_id WHERE f.started_at>=? GROUP BY f.user_id,u.username ORDER BY minutes DESC LIMIT 20", start);
    }

    public List<Map<String, Object>> abnormalSessions() {
        return db.queryForList("SELECT f.id,f.user_id,u.username,f.duration_minutes,f.subject,f.source,f.started_at,f.ended_at FROM focus_sessions f JOIN users u ON u.id=f.user_id WHERE f.duration_minutes>240 OR f.duration_minutes<1 ORDER BY f.started_at DESC LIMIT 30");
    }

    public Map<String, Object> findSession(long sessionId) {
        return db.queryForList("SELECT * FROM focus_sessions WHERE id=?", sessionId).stream().findFirst().orElse(null);
    }

    public void deleteSession(long sessionId) {
        db.update("DELETE FROM focus_sessions WHERE id=?", sessionId);
    }

    public Map<String, Object> roomSummary() {
        return db.queryForList("SELECT COUNT(*) AS total,COALESCE(SUM(status='active'),0) AS active,COALESCE(SUM(status='closed'),0) AS closed,COALESCE(SUM(visibility='private'),0) AS private_rooms,COALESCE(SUM(focus_minutes),0) AS focus_minutes FROM study_rooms").stream().findFirst().orElse(null);
    }

    public Map<String, Object> activeMembers() {
        return db.queryForList("SELECT COUNT(*) AS members FROM study_room_members WHERE last_active_at>=DATE_SUB(NOW(),INTERVAL 1 DAY)").stream().findFirst().orElse(null);
    }

    public List<Map<String, Object>> roomLeaders() {
        return db.queryForList("SELECT r.id,r.name,r.focus_minutes,u.username AS owner_username FROM study_rooms r JOIN users u ON u.id=r.owner_id ORDER BY r.focus_minutes DESC LIMIT 10");
    }

    public Map<String, Object> findRoom(long roomId) {
        return db.queryForList("SELECT * FROM study_rooms WHERE id=?", roomId).stream().findFirst().orElse(null);
    }

    public void updateRoom(String status, boolean muted, long roomId) {
        db.update("UPDATE study_rooms SET status=?,muted=? WHERE id=?", status, muted, roomId);
    }

    public void recordAdminClose(long roomId, long actor) {
        db.update("INSERT INTO study_room_messages(room_id,user_id,kind,content) VALUES(?,?,'system','管理员关闭了该自习室')", roomId, actor);
    }

    public void deleteRoom(long roomId) {
        db.update("DELETE FROM study_rooms WHERE id=?", roomId);
    }

    public List<Map<String, Object>> rooms(String status) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT r.*,u.username AS owner_username,(SELECT COUNT(*) FROM study_room_members m WHERE m.room_id=r.id) AS members,(SELECT COUNT(*) FROM study_room_messages x WHERE x.room_id=r.id) AS messages FROM study_rooms r JOIN users u ON u.id=r.owner_id WHERE 1=1");
        if (status != null && !status.isBlank() && !"all".equals(status)) {
            sql.append(" AND r.status=?");
            args.add(status);
        }
        sql.append(" ORDER BY r.created_at DESC LIMIT 100");
        return db.queryForList(sql.toString(), args.toArray());
    }
}
