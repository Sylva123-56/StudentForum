package com.studentforum.repository;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StudyPlanRepository {
    private final JdbcTemplate db;

    public StudyPlanRepository(JdbcTemplate db) {
        this.db = db;
    }

    public List<Map<String, Object>> progress(long planId) {
        return db.queryForList("SELECT COUNT(*) AS total,COALESCE(SUM(status='done'),0) AS done,COALESCE(SUM(status='done' AND DATE(completed_at)=CURRENT_DATE()),"
                + "0) AS done_today,COALESCE(SUM(status='pending' AND due_date<CURRENT_DATE()),0) AS overdue FROM study_tasks WHERE plan_id=?"
                + " AND parent_id IS NULL", planId);
    }

    public List<Map<String, Object>> tasks(long planId) {
        return db.queryForList("SELECT t.*,(SELECT COUNT(*) FROM study_tasks c WHERE c.parent_id=t.id) AS child_count FROM study_tasks t WHERE t.plan_id=?"
                + " ORDER BY t.parent_id IS NULL DESC,t.sort_order,t.id", planId);
    }

    public Map<String, Object> parent(long taskId) {
        return db.queryForList("SELECT id,parent_id FROM study_tasks WHERE id=?", taskId).stream().findFirst().orElse(null);
    }

    public long children(long taskId) {
        Long count = db.queryForObject("SELECT COUNT(*) AS count FROM study_tasks WHERE parent_id=?", Long.class, taskId);
        return count == null ? 0 : count;
    }

    public long doneChildren(long taskId) {
        Long count = db.queryForObject("SELECT COUNT(*) AS count FROM study_tasks WHERE parent_id=? AND status='done'", Long.class, taskId);
        return count == null ? 0 : count;
    }

    public void updateParent(long taskId, String status, LocalDateTime completedAt) {
        db.update("UPDATE study_tasks SET status=?,completed_at=? WHERE id=?", status, completedAt, taskId);
    }

    public Map<String, Object> planStatus(long planId) {
        return db.queryForList("SELECT status FROM study_plans WHERE id=?", planId).stream().findFirst().orElse(null);
    }

    public void updatePlanStatus(long planId, String status) {
        String sql = "completed".equals(status)
                ? "UPDATE study_plans SET status='completed' WHERE id=?"
                : "UPDATE study_plans SET status='active' WHERE id=?";
        db.update(sql, planId);
    }

    public Map<String, Object> findOwnedPlan(long planId, long user) {
        return db.queryForList("SELECT * FROM study_plans WHERE id=? AND user_id=?", planId, user).stream().findFirst().orElse(null);
    }

    public Map<String, Object> focusMinutes(long planId) {
        return db.queryForList("SELECT COALESCE(SUM(duration_minutes),0) AS minutes FROM focus_sessions WHERE plan_id=?", planId).stream().findFirst().orElse(null);
    }

    public Map<String, Object> groupName(long groupId) {
        return db.queryForList("SELECT name FROM study_groups WHERE id=?", groupId).stream().findFirst().orElse(null);
    }

    public Map<String, Object> groupMembership(Long groupId, long user) {
        return db.queryForList("SELECT 1 FROM study_group_members WHERE group_id=? AND user_id=?", groupId, user).stream().findFirst().orElse(null);
    }

    public long insertPlan(long user, String title, String subject, String goal, LocalDate start, LocalDate end, Long groupId) {
        db.update("INSERT INTO study_plans(user_id,title,subject,goal,start_date,end_date,status,group_id) VALUES(?,?,?,?,?,?,'active',?)", user, title, subject, goal, start, end, groupId);
        return db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public void insertTask(long planId, String title, LocalDate due, int order) {
        db.update("INSERT INTO study_tasks(plan_id,title,due_date,status,sort_order) VALUES(?,?,?,'pending',?)", planId, title, due, order);
    }

    public Map<String, Object> planCount(long user) {
        return db.queryForList("SELECT COUNT(*) AS count FROM study_plans WHERE user_id=? AND status<>'archived'", user).stream().findFirst().orElse(null);
    }

    public Map<String, Object> activePlanCount(long user) {
        return db.queryForList("SELECT COUNT(*) AS count FROM study_plans WHERE user_id=? AND status='active'", user).stream().findFirst().orElse(null);
    }

    public Map<String, Object> completedPlanCount(long user) {
        return db.queryForList("SELECT COUNT(*) AS count FROM study_plans WHERE user_id=? AND status='completed'", user).stream().findFirst().orElse(null);
    }

    public Map<String, Object> taskCount(long user) {
        return db.queryForList("SELECT COUNT(*) AS count FROM study_tasks t JOIN study_plans p ON p.id=t.plan_id WHERE p.user_id=? AND t.parent_id IS NULL", user).stream().findFirst().orElse(null);
    }

    public Map<String, Object> doneTaskCount(long user) {
        return db.queryForList("SELECT COUNT(*) AS count FROM study_tasks t JOIN study_plans p ON p.id=t.plan_id WHERE p.user_id=? AND t.parent_id IS NULL AND t.status='done'", user).stream().findFirst().orElse(null);
    }

    public Map<String, Object> tasksDueToday(long user) {
        return db.queryForList("SELECT COUNT(*) AS count FROM study_tasks t JOIN study_plans p ON p.id=t.plan_id WHERE p.user_id=? AND t.status='pending' AND t.due_date=CURRENT_DATE()", user).stream().findFirst().orElse(null);
    }

    public Map<String, Object> overdueTasks(long user) {
        return db.queryForList("SELECT COUNT(*) AS count FROM study_tasks t JOIN study_plans p ON p.id=t.plan_id WHERE p.user_id=? AND t.status='pending' AND t.due_date<CURRENT_DATE()", user).stream().findFirst().orElse(null);
    }

    public List<Map<String, Object>> completedDays(long user) {
        return db.queryForList("SELECT DATE(t.completed_at) AS date FROM study_tasks t JOIN study_plans p ON p.id=t.plan_id WHERE p.user_id=? AND t.completed_at IS NOT NULL GROUP BY DATE(t.completed_at) ORDER BY date DESC LIMIT 400", user);
    }

    public void updatePlan(String title, String subject, String goal, LocalDate start, LocalDate end, String status, Long groupId, long planId) {
        db.update("UPDATE study_plans SET title=?,subject=?,goal=?,start_date=?,end_date=?,status=?,group_id=? WHERE id=?", title, subject, goal, start, end, status, groupId, planId);
    }

    public void setPlanStatus(String status, long planId) {
        db.update("UPDATE study_plans SET status=? WHERE id=?", status, planId);
    }

    public void deletePlan(long planId) {
        db.update("DELETE FROM study_plans WHERE id=?", planId);
    }

    public Map<String, Object> findParentTask(Long taskId, long planId) {
        return db.queryForList("SELECT id,parent_id FROM study_tasks WHERE id=? AND plan_id=?", taskId, planId).stream().findFirst().orElse(null);
    }

    public Map<String, Object> nextTaskOrder(long planId) {
        return db.queryForList("SELECT COALESCE(MAX(sort_order),-1)+1 AS next FROM study_tasks WHERE plan_id=?", planId).stream().findFirst().orElse(null);
    }

    public long insertChildTask(long planId, Long parentId, String title, LocalDate due, long order) {
        db.update("INSERT INTO study_tasks(plan_id,parent_id,title,due_date,status,sort_order) VALUES(?,?,?,?,'pending',?)", planId, parentId, title, due, order);
        return db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public Map<String, Object> findTask(long taskId, long planId) {
        return db.queryForList("SELECT * FROM study_tasks WHERE id=? AND plan_id=?", taskId, planId).stream().findFirst().orElse(null);
    }

    public Map<String, Object> childCount(long taskId) {
        return db.queryForList("SELECT COUNT(*) AS count FROM study_tasks WHERE parent_id=?", taskId).stream().findFirst().orElse(null);
    }

    public void updateTaskTitle(String title, long taskId) {
        db.update("UPDATE study_tasks SET title=? WHERE id=?", title, taskId);
    }

    public void updateTaskDueDate(LocalDate due, long taskId) {
        db.update("UPDATE study_tasks SET due_date=? WHERE id=?", due, taskId);
    }

    public void updateTaskStatus(String status, LocalDateTime completedAt, long taskId) {
        db.update("UPDATE study_tasks SET status=?,completed_at=? WHERE id=?", status, completedAt, taskId);
    }

    public Map<String, Object> planReward(long user, long planId) {
        return db.queryForList("SELECT 1 FROM point_logs WHERE user_id=? AND ref_type='study_plan' AND ref_id=?", user, planId).stream().findFirst().orElse(null);
    }

    public Map<String, Object> activeSubjectGoal(long user, String subject) {
        return db.queryForList("SELECT * FROM checkin_goals WHERE user_id=? AND status='active' AND subject=? ORDER BY id LIMIT 1", user, subject).stream().findFirst().orElse(null);
    }

    public Map<String, Object> firstActiveGoal(long user) {
        return db.queryForList("SELECT * FROM checkin_goals WHERE user_id=? AND status='active' ORDER BY id LIMIT 1", user).stream().findFirst().orElse(null);
    }

    public Map<String, Object> todayCheckinCount(long goalId) {
        return db.queryForList("SELECT COUNT(*) AS count FROM checkins WHERE goal_id=? AND checkin_date=CURRENT_DATE()", goalId).stream().findFirst().orElse(null);
    }

    public void insertPlanCheckin(long user, long goalId, String content, int duration, String subject, String visibility, long planId, long taskId) {
        db.update("INSERT INTO checkins(user_id,goal_id,content,duration_minutes,subject,visibility,checkin_date,make_up,source,plan_id,task_id) VALUES(?,?,?,?,?,?,CURRENT_DATE(),FALSE,'plan',?,?)", user, goalId, content, duration, subject, visibility, planId, taskId);
    }

    public Map<String, Object> todayCheckin(long goalId) {
        return db.queryForList("SELECT * FROM checkins WHERE goal_id=? AND checkin_date=CURRENT_DATE()", goalId).stream().findFirst().orElse(null);
    }

    public void deleteTask(long taskId) {
        db.update("DELETE FROM study_tasks WHERE id=?", taskId);
    }

    public long insertTemplate(long user, String title, String subject, String goal, int cycle, String tasks, String visibility) {
        db.update("INSERT INTO plan_templates(owner_id,title,subject,goal,cycle_days,tasks,visibility) VALUES(?,?,?,?,?,?,?)", user, title, subject, goal, cycle, tasks, visibility);
        return db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public Map<String, Object> findTemplate(long templateId) {
        return db.queryForList("SELECT * FROM plan_templates WHERE id=?", templateId).stream().findFirst().orElse(null);
    }

    public long insertTemplatePlan(long user, String title, String subject, String goal, LocalDate start, LocalDate end, Long groupId, long templateId) {
        db.update("INSERT INTO study_plans(user_id,title,subject,goal,start_date,end_date,status,group_id,template_id) VALUES(?,?,?,?,?,?,'active',?,?)", user, title, subject, goal, start, end, groupId, templateId);
        return db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public void incrementTemplateUse(long templateId) {
        db.update("UPDATE plan_templates SET use_count=use_count+1 WHERE id=?", templateId);
    }

    public void deleteTemplate(long templateId) {
        db.update("DELETE FROM plan_templates WHERE id=?", templateId);
    }

    public List<Map<String, Object>> plans(long user, String status, String subject) {
        List<Object> args = new ArrayList<>();
        args.add(user);
        StringBuilder sql = new StringBuilder("SELECT p.*,u.username AS owner_username FROM study_plans p JOIN users u ON u.id=p.user_id WHERE p.user_id=?");
        if (status != null && !status.isBlank() && !"all".equals(status)) {
            sql.append(" AND p.status=?");
            args.add(status);
        }
        if (subject != null && !subject.isBlank()) {
            sql.append(" AND p.subject=?");
            args.add(subject.trim());
        }
        sql.append(" ORDER BY p.status='active' DESC,p.end_date IS NULL,p.end_date,p.id DESC LIMIT 100");
        return db.queryForList(sql.toString(), args.toArray());
    }

    public List<Map<String, Object>> officialTemplates(String subject) {
        String filter = subject == null || subject.isBlank() ? "" : " AND subject=?";
        List<Object> args = new ArrayList<>();
        if (subject != null && !subject.isBlank()) args.add(subject.trim());
        return db.queryForList("SELECT t.*,u.username AS owner_username FROM plan_templates t LEFT JOIN users u ON u.id=t.owner_id WHERE t.owner_id IS NULL" + filter + " ORDER BY t.id", args.toArray());
    }

    public List<Map<String, Object>> ownedTemplates(long user, String subject) {
        String filter = subject == null || subject.isBlank() ? "" : " AND subject=?";
        List<Object> args = new ArrayList<>();
        args.add(user);
        if (subject != null && !subject.isBlank()) args.add(subject.trim());
        return db.queryForList("SELECT t.*,u.username AS owner_username FROM plan_templates t LEFT JOIN users u ON u.id=t.owner_id WHERE t.owner_id=?" + filter + " ORDER BY t.id DESC", args.toArray());
    }

    public List<Map<String, Object>> sharedTemplates(long user, String subject) {
        String filter = subject == null || subject.isBlank() ? "" : " AND subject=?";
        List<Object> args = new ArrayList<>();
        args.add(user);
        if (subject != null && !subject.isBlank()) args.add(subject.trim());
        return db.queryForList("SELECT t.*,u.username AS owner_username FROM plan_templates t JOIN users u ON u.id=t.owner_id WHERE t.owner_id IS NOT NULL AND t.owner_id<>? AND t.visibility='shared'" + filter + " ORDER BY t.use_count DESC,t.id DESC LIMIT 50", args.toArray());
    }
}
