package com.studentforum.service;

import com.studentforum.repository.StudyPlanRepository;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StudyPlanProgressService {
    private final StudyPlanRepository repository;

    public StudyPlanProgressService(StudyPlanRepository repository) {
        this.repository = repository;
    }

    // ---------- 计划进度 ----------

    /**
     * 计划进度：分母只数顶层任务（parent_id IS NULL），
     * 子任务只是"把这件事拆小"的清单，重复计入会让百分比永远到不了 100%。
     */
    public Map<String, Object> progress(long planId) {
        List<Map<String, Object>> rows = repository.progress(planId);
        Map<String, Object> row = rows.isEmpty() ? new LinkedHashMap<>() : rows.getFirst();
        long total = number(row, "total"), done = number(row, "done");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("done", done);
        result.put("percent", total == 0 ? 0 : Math.round(done * 100.0 / total));
        result.put("doneToday", number(row, "done_today"));
        result.put("overdue", number(row, "overdue"));
        return result;
    }

    /** 计划下全部任务（含子任务的 child_count），详情页和模板复制都靠它。 */
    public List<Map<String, Object>> planTasks(long planId) {
        return repository.tasks(planId);
    }

    /**
     * 有子任务的父任务不直接勾选：它的完成状态由子任务推导，
     * 否则用户勾一下父任务就能把整块进度刷满，进度条就失去意义了。
     */
    @Transactional
    public void rollupTask(long taskId) {
        Map<String, Object> task = repository.parent(taskId);
        if (task == null || task.get("parent_id") != null) return;
        long children = repository.children(taskId);
        if (children == 0) return;
        long done = repository.doneChildren(taskId);
        repository.updateParent(taskId, done == 0 ? "pending" : "done", done == 0 ? null : LocalDateTime.now());
    }

    /** 计划里的任务全勾完就自动置为 completed；取消勾选时再退回 active。 */
    @Transactional
    public void syncPlanStatus(long planId) {
        Map<String, Object> plan = repository.planStatus(planId);
        if (plan == null) return;
        String status = String.valueOf(plan.get("status"));
        if ("archived".equals(status)) return;
        Map<String, Object> progress = progress(planId);
        long total = number(progress, "total"), done = number(progress, "done");
        if (total > 0 && done == total && !"completed".equals(status)) repository.updatePlanStatus(planId, "completed");
        if (done < total && "completed".equals(status)) repository.updatePlanStatus(planId, "active");
    }

    private long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.longValue() : 0;
    }
}
