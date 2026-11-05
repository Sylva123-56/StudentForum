package com.studentforum.repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CheckinRepository {
    private final JdbcTemplate db;

    public CheckinRepository(JdbcTemplate db) {
        this.db = db;
    }

    public List<Map<String, Object>> goals(long user, String filter) {
        String clause = switch (filter) {
            case "archived" -> "AND g.status='archived'";
            case "all" -> "";
            default -> "AND g.status='active'";
        };
        return db.queryForList("SELECT g.*,u.username AS owner_username,u.id AS owner_id,"
                + "(SELECT COUNT(*) FROM checkins c WHERE c.goal_id=g.id) AS checkin_count,"
                + "(SELECT COUNT(*) FROM checkins c WHERE c.goal_id=g.id AND c.checkin_date=CURRENT_DATE()) AS today_count,"
                + "(SELECT COALESCE(SUM(c.duration_minutes),0) FROM checkins c WHERE c.goal_id=g.id AND c.checkin_date=CURRENT_DATE()) AS today_minutes,"
                + "(SELECT MAX(c.checkin_date) FROM checkins c WHERE c.goal_id=g.id) AS last_checkin "
                + "FROM checkin_goals g JOIN users u ON u.id=g.user_id WHERE g.user_id=? " + clause + " ORDER BY FIELD(g.status,'active','archived'),g.created_at DESC", user);
    }

    public List<Map<String, Object>> publicGoals(Long me, String subject) {
        StringBuilder sql = new StringBuilder("SELECT g.id,g.title,g.subject,g.frequency,g.target_minutes,g.visibility,g.created_at,u.username AS owner_username,u.id AS owner_id,"
                + "(SELECT COUNT(*) FROM checkins c WHERE c.goal_id=g.id) AS checkin_count,"
                + "(SELECT MAX(c.checkin_date) FROM checkins c WHERE c.goal_id=g.id) AS last_checkin "
                + "FROM checkin_goals g JOIN users u ON u.id=g.user_id WHERE g.status='active' AND u.status='active' AND (g.visibility='public'");
        List<Object> args = new ArrayList<>();
        if (me != null) {
            sql.append(" OR (g.visibility='group' AND g.group_id IN (SELECT group_id FROM study_group_members WHERE user_id=?))");
            args.add(me);
        }
        sql.append(")");
        if (subject != null && !subject.isBlank()) {
            sql.append(" AND g.subject=?");
            args.add(subject.trim());
        }
        sql.append(" ORDER BY last_checkin DESC,g.created_at DESC LIMIT 60");
        return db.queryForList(sql.toString(), args.toArray());
    }

    public List<Map<String, Object>> records(long user, Long goalId, int offset) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT c.*,g.title AS goal_title,g.status AS goal_status,u.username,"
                + "(SELECT COUNT(*) FROM checkins c2 WHERE c2.user_id=c.user_id AND c2.checkin_date=c.checkin_date) AS same_day_count "
                + "FROM checkins c JOIN checkin_goals g ON g.id=c.goal_id JOIN users u ON u.id=c.user_id WHERE c.user_id=?");
        args.add(user);
        if (goalId != null) {
            sql.append(" AND c.goal_id=?");
            args.add(goalId);
        }
        sql.append(" ORDER BY c.checkin_date DESC,c.id DESC LIMIT 21 OFFSET ?");
        args.add(offset);
        return db.queryForList(sql.toString(), args.toArray());
    }

    public Long todayCount(long user) {
        return db.queryForObject("SELECT COUNT(*) FROM checkins WHERE user_id=? AND checkin_date=CURRENT_DATE()", Long.class, user);
    }

    public Long totalCount(long user) {
        return db.queryForObject("SELECT COUNT(*) FROM checkins WHERE user_id=?", Long.class, user);
    }

    public Long makeupCount(long user) {
        return db.queryForObject("SELECT COUNT(*) FROM checkins WHERE user_id=? AND make_up=TRUE", Long.class, user);
    }

    public Long totalPoints(long user) {
        return db.queryForObject("SELECT COALESCE(SUM(points),0) FROM checkins WHERE user_id=?", Long.class, user);
    }

    public Long totalMinutes(long user) {
        return db.queryForObject("SELECT COALESCE(SUM(duration_minutes),0) FROM checkins WHERE user_id=?", Long.class, user);
    }

    public Long activeGoals(long user) {
        return db.queryForObject("SELECT COUNT(*) FROM checkin_goals WHERE user_id=? AND status='active'", Long.class, user);
    }

    public List<Map<String, Object>> pendingReminders(long user) {
        return db.queryForList("SELECT g.id,g.title,g.subject,g.reminder_time FROM checkin_goals g WHERE g.user_id=? AND g.status='active'"
                + " AND g.reminder_time IS NOT NULL AND NOT EXISTS (SELECT 1 FROM checkins c WHERE c.goal_id=g.id AND c.checkin_date=CURRENT_DATE())"
                + " ORDER BY g.reminder_time,g.id", user);
    }

    public List<Map<String, Object>> calendar(long user, LocalDate start, LocalDate end) {
        return db.queryForList("SELECT checkin_date,COUNT(*) AS count,COALESCE(SUM(duration_minutes),0) AS minutes,COALESCE(SUM(points),0) AS points FROM checkins"
                + " WHERE user_id=? AND checkin_date BETWEEN ? AND ? GROUP BY checkin_date ORDER BY checkin_date", user, start, end);
    }

    public long insertGoal(long user, String title, String subject, String frequency, int targetMinutes, String visibility, Long groupId, boolean allowMakeup, String reminder) {
        db.update("INSERT INTO checkin_goals(user_id,title,subject,frequency,target_minutes,visibility,group_id,allow_makeup,reminder_time) VALUES(?,?,?,?,?,?,?,?,?)", user, title, subject, frequency, targetMinutes, visibility, groupId, allowMakeup, reminder);
        return db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public void updateGoal(String title, String subject, String frequency, int targetMinutes, String visibility, Long groupId, boolean allowMakeup, String reminder, long goalId) {
        db.update("UPDATE checkin_goals SET title=?,subject=?,frequency=?,target_minutes=?,visibility=?,group_id=?,allow_makeup=?,reminder_time=? WHERE id=?", title, subject, frequency, targetMinutes, visibility, groupId, allowMakeup, reminder, goalId);
    }

    public void setGoalStatus(String status, long goalId) {
        db.update("UPDATE checkin_goals SET status=? WHERE id=?", status, goalId);
    }

    public void deleteGoal(long goalId) {
        db.update("DELETE FROM checkin_goals WHERE id=?", goalId);
    }

    public Map<String, Object> ownedGoal(long goalId, long user) {
        return db.queryForList("SELECT * FROM checkin_goals WHERE id=? AND user_id=?", goalId, user).stream().findFirst().orElse(null);
    }

    public Long goalCountOnDate(long goalId, LocalDate date) {
        return db.queryForObject("SELECT COUNT(*) FROM checkins WHERE goal_id=? AND checkin_date=?", Long.class, goalId, date);
    }

    public Long userCountOnDate(long user, LocalDate date) {
        return db.queryForObject("SELECT COUNT(*) FROM checkins WHERE user_id=? AND checkin_date=?", Long.class, user, date);
    }

    public long insertCheckin(long user, long goalId, String content, int duration, String subject, String image,
            String visibility, LocalDate date, boolean makeUp, String source, Long planId, Long taskId) {
        db.update("INSERT INTO checkins(user_id,goal_id,content,duration_minutes,subject,image_url,visibility,checkin_date,make_up,source,plan_id,task_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                user, goalId, content, duration, subject, image, visibility, date, makeUp, source, planId, taskId);
        return db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public void setCheckinPoints(int points, long checkinId) {
        db.update("UPDATE checkins SET points=? WHERE id=?", points, checkinId);
    }

    public Map<String, Object> checkin(long checkinId) {
        return db.queryForList("SELECT * FROM checkins WHERE id=?", checkinId).stream().findFirst().orElse(null);
    }

    public List<Map<String, Object>> goalRecords(long goalId, int span) {
        return db.queryForList("SELECT * FROM checkins WHERE goal_id=? AND checkin_date>=DATE_SUB(CURRENT_DATE(),INTERVAL ? DAY) ORDER BY checkin_date DESC", goalId, span);
    }

    public Map<String, Object> goalSummary(long goalId) {
        return db.queryForList("SELECT g.*,(SELECT COUNT(*) FROM checkins c WHERE c.goal_id=g.id) AS checkin_count,(SELECT COUNT(*) FROM checkins c WHERE c.goal_id=g.id"
                + " AND c.checkin_date=CURRENT_DATE()) AS today_count FROM checkin_goals g WHERE g.id=?", goalId).stream().findFirst().orElse(null);
    }

    public boolean groupMembership(long groupId, long user) {
        return !db.queryForList("SELECT 1 FROM study_group_members WHERE group_id=? AND user_id=?", groupId, user).isEmpty();
    }

    public List<Map<String, Object>> recent(long goalId) {
        return db.queryForList("SELECT * FROM checkins WHERE goal_id=? ORDER BY checkin_date DESC LIMIT 30", goalId);
    }

    public Map<String, Object> goal(long goalId) {
        return db.queryForList("SELECT * FROM checkin_goals WHERE id=?", goalId).stream().findFirst().orElse(null);
    }

    public Long activeGroupMembership(long groupId, long userId) {
        return db.queryForObject("SELECT COUNT(*) FROM study_group_members m JOIN study_groups g ON g.id=m.group_id WHERE m.group_id=? AND m.user_id=? AND g.status='active'", Long.class, groupId, userId);
    }

    public List<Map<String, Object>> userDates(long userId) {
        return db.queryForList("SELECT DISTINCT checkin_date FROM checkins WHERE user_id=? ORDER BY checkin_date DESC LIMIT 400", userId);
    }

    public List<Map<String, Object>> goalDates(long goalId, long userId) {
        return db.queryForList("SELECT checkin_date FROM checkins WHERE goal_id=? AND user_id=? ORDER BY checkin_date DESC LIMIT 400", goalId, userId);
    }

    public long totalDays(long userId) {
        Long days = db.queryForObject("SELECT COUNT(DISTINCT checkin_date) FROM checkins WHERE user_id=?", Long.class, userId);
        return days == null ? 0 : days;
    }
}
