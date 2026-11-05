package com.studentforum.repository;

import java.time.LocalDateTime;
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
}
