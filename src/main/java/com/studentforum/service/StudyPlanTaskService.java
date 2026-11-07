package com.studentforum.service;

import com.studentforum.repository.StudyPlanRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 计划任务的增删改、父任务汇总、奖励与可选打卡，统一在任务写入事务内完成。 */
@Service
public class StudyPlanTaskService {
    private final StudyPlanRepository repository;
    private final AccountAccessService accounts;
    private final StudyToolService tools;
    private final StudySettingsService studySettings;
    private final StudyRewardService rewards;
    private final StudyPlanProgressService progress;
    private final StudyPlanService plans;

    public StudyPlanTaskService(StudyPlanRepository repository, AccountAccessService accounts,
                                StudyToolService tools, StudySettingsService studySettings,
                                StudyRewardService rewards, StudyPlanProgressService progress,
                                StudyPlanService plans) {
        this.repository = repository;
        this.accounts = accounts;
        this.tools = tools;
        this.studySettings = studySettings;
        this.rewards = rewards;
        this.progress = progress;
        this.plans = plans;
    }

    private void check(boolean condition, String message) {
        if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private Map<String, Object> required(Map<String, Object> row, String message) {
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
        return row;
    }

    private Map<String, Object> plan(long planId, long user) {
        return required(repository.findOwnedPlan(planId, user), "学习计划不存在");
    }

    private boolean booleanFlag(Map<String, Object> input, String key) {
        Object value = input.get(key);
        return value instanceof Boolean flag ? flag : "true".equals(String.valueOf(value));
    }

    @Transactional
    public Map<String, Object> addTask(Authentication auth, long planId, Map<String, Object> input) {
        accounts.writable(auth);
        long user = accounts.id(auth);
        plan(planId, user);
        String title = tools.text(input, "title", 1, 100, "任务标题需要 1 到 100 个字");
        LocalDate due = input.get("dueDate") == null ? null : tools.date(input, "dueDate", null);
        Long parentId = input.get("parentId") == null ? null : tools.number(input, "parentId", 0);
        if (parentId != null && parentId > 0) {
            Map<String, Object> parent = required(repository.findParentTask(parentId, planId), "父任务不存在");
            // 只支持一层子任务：再深一层用户自己也看不懂进度条
            check(parent.get("parent_id") == null, "子任务下不能再建子任务");
        } else parentId = null;
        long order = tools.rowNumber(repository.nextTaskOrder(planId), "next");
        repository.insertChildTask(planId, parentId, title, due, order);
        if (parentId != null) progress.rollupTask(parentId);
        progress.syncPlanStatus(planId);
        return plans.plan(auth, planId);
    }

    /**
     * 勾选 / 取消勾选任务。
     * 只有"从未完成变成完成"才发积分——反复勾选刷分是这类系统最常见的漏洞。
     */
    @Transactional
    public Map<String, Object> updateTask(Authentication auth, long planId, long taskId, Map<String, Object> input) {
        accounts.writable(auth);
        long user = accounts.id(auth);
        Map<String, Object> before = plan(planId, user);
        String statusBefore = String.valueOf(before.get("status"));
        Map<String, Object> task = required(repository.findTask(taskId, planId), "计划任务不存在");
        long children = tools.rowNumber(repository.childCount(taskId), "count");
        if (children > 0) check(!input.containsKey("status"), "该任务包含子任务，请勾选子任务来推进进度");

        if (input.containsKey("title")) repository.updateTaskTitle(tools.text(input, "title", 1, 100, "任务标题需要 1 到 100 个字"), taskId);
        if (input.containsKey("dueDate")) repository.updateTaskDueDate(input.get("dueDate") == null ? null : tools.date(input, "dueDate", null), taskId);

        int points = 0;
        List<Map<String, Object>> badges = new ArrayList<>();
        Map<String, Object> checkin = null;
        if (input.containsKey("status")) {
            String status = String.valueOf(input.get("status"));
            check("pending".equals(status) || "done".equals(status), "任务状态无效");
            boolean wasDone = "done".equals(task.get("status"));
            boolean nowDone = "done".equals(status);
            if (wasDone != nowDone) {
                repository.updateTaskStatus(status, nowDone ? LocalDateTime.now() : null, taskId);
                if (nowDone && repository.taskReward(user, taskId) == null) {
                    Map<String, Object> settings = studySettings.settings();
                    int reward = studySettings.setting(settings, "plan_task_points", 1);
                    int cap = studySettings.setting(settings, "plan_task_daily_cap", 10);
                    points += reward;
                    badges.addAll(rewards.award(user, "plan_task", reward, "study_task", taskId, cap));
                }
                if (task.get("parent_id") != null) progress.rollupTask(tools.rowNumber(task, "parent_id"));
                if (nowDone && booleanFlag(input, "checkin")) checkin = autoCheckin(user, before, taskId);
            }
        }
        progress.syncPlanStatus(planId);

        // 这一步刚好把整个计划做完：额外给一次"完成计划"奖励，且按 point_logs 去重只发一次
        if (!"completed".equals(statusBefore) && "completed".equals(String.valueOf(plan(planId, user).get("status")))) {
            Map<String, Object> settings = studySettings.settings();
            int bonus = studySettings.setting(settings, "plan_complete_points", 10);
            boolean already = repository.planReward(user, planId) != null;
            if (!already && bonus > 0) {
                points += bonus;
                badges.addAll(rewards.award(user, "plan_complete", bonus, "study_plan", planId, 0));
            }
        }
        Map<String, Object> result = plans.plan(auth, planId);
        result.put("pointsAwarded", points);
        result.put("badges", badges);
        result.put("checkin", checkin);
        return result;
    }

    /**
     * 完成计划任务后自动记一条打卡（设计稿的"完成计划任务自动记录 / 与打卡联动"）。
     * 没有匹配科目的打卡目标时不报错，只回一个 null，不打断勾任务这个主流程。
     */
    private Map<String, Object> autoCheckin(long user, Map<String, Object> plan, long taskId) {
        String subject = plan.get("subject") == null ? "" : String.valueOf(plan.get("subject"));
        Map<String, Object> goal = repository.activeSubjectGoal(user, subject);
        if (goal == null) goal = repository.firstActiveGoal(user);
        if (goal == null) return null;
        long goalId = tools.rowNumber(goal, "id");
        long exists = tools.rowNumber(repository.todayCheckinCount(goalId), "count");
        if (exists > 0) return null;
        int duration = (int) tools.rowNumber(goal, "target_minutes");
        repository.insertPlanCheckin(user, goalId, "完成计划任务自动打卡", duration, subject, String.valueOf(goal.get("visibility")), tools.rowNumber(plan, "id"), taskId);
        return repository.todayCheckin(goalId);
    }

    @Transactional
    public Map<String, Object> deleteTask(Authentication auth, long planId, long taskId) {
        accounts.writable(auth);
        long user = accounts.id(auth);
        plan(planId, user);
        Map<String, Object> task = required(repository.findTask(taskId, planId), "计划任务不存在");
        repository.deleteTask(taskId);
        if (task.get("parent_id") != null) progress.rollupTask(tools.rowNumber(task, "parent_id"));
        progress.syncPlanStatus(planId);
        return plans.plan(auth, planId);
    }
}
