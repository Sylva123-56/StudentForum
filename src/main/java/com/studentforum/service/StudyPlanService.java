package com.studentforum.service;

import com.studentforum.repository.StudyPlanRepository;

import com.studentforum.model.StudyPlanPeriod;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 学习计划：计划 CRUD、模板生成计划和统计；任务勾选由 StudyPlanTaskService 负责。
 * 计划、任务、专注记录、打卡共用一条"做了多少"的账，勾任务会回写积分和徽章。
 */
@Service
public class StudyPlanService {
    private static final Set<String> STATUSES = Set.of("active", "completed", "archived");

    private final StudyPlanRepository repository;
    private final AccountAccessService accounts;
    private final StudyToolService tools;
    private final StudyPreferencesService preferences;
    private final StudyPlanProgressService progress;
    private final StudyPlanTemplateService templates;

    public StudyPlanService(StudyPlanRepository repository, AccountAccessService accounts,
                            StudyToolService tools, StudyPreferencesService preferences,
                            StudyPlanProgressService progress, StudyPlanTemplateService templates) {
        this.repository = repository;
        this.accounts = accounts;
        this.tools = tools;
        this.preferences = preferences;
        this.progress = progress;
        this.templates = templates;
    }

    private long id(Authentication auth) {
        return accounts.id(auth);
    }

    private void check(boolean condition, String message) {
        if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private Map<String, Object> required(Map<String, Object> row, String message) {
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
        return row;
    }

    private boolean booleanFlag(Map<String, Object> input, String key) {
        Object value = input.get(key);
        return value instanceof Boolean flag ? flag : "true".equals(String.valueOf(value));
    }

    /** 计划归属：读自己的计划要 404 掉别人的，避免越权看到私密计划内容。 */
    private Map<String, Object> plan(long planId, long user) {
        return required(repository.findOwnedPlan(planId, user), "学习计划不存在");
    }

    private Map<String, Object> detail(long planId, long user) {
        Map<String, Object> plan = new LinkedHashMap<>(plan(planId, user));
        plan.put("tasks", progress.planTasks(planId));
        plan.put("progress", progress.progress(planId));
        plan.put("focusMinutes", tools.rowNumber(repository.focusMinutes(planId), "minutes"));
        if (plan.get("group_id") != null) {
            Map<String, Object> group = repository.groupName(tools.rowNumber(plan, "group_id"));
            plan.put("groupName", group == null ? null : group.get("name"));
        } else {
            plan.put("groupName", null);
        }
        return plan;
    }

    // ---------- 计划 ----------

    public Map<String, Object> plans(Authentication auth,
                              String status,
                              String subject) {
        long user = id(auth);
        if (status != null && !status.isBlank() && !"all".equals(status)) check(STATUSES.contains(status), "计划状态无效");
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : repository.plans(user, status, subject)) {
            Map<String, Object> plan = new LinkedHashMap<>(row);
            plan.put("progress", progress.progress(tools.rowNumber(row, "id")));
            items.add(plan);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("stats", stats(user));
        result.put("subjects", preferences.subjects(user));
        return result;
    }

    @Transactional
    public Map<String, Object> createPlan(Authentication auth, Map<String, Object> input) {
        accounts.writable(auth);
        long user = id(auth);
        String title = tools.text(input, "title", 2, 60, "计划标题需要 2 到 60 个字");
        String subject = tools.optionalText(input, "subject", 40);
        String goal = tools.optionalText(input, "goal", 200);
        LocalDate start = tools.date(input, "startDate", LocalDate.now());
        LocalDate end = input.get("endDate") == null ? null : tools.date(input, "endDate", null);
        check(new StudyPlanPeriod(start, end).isValid(), "结束日期不能早于开始日期");
        Long groupId = input.get("groupId") == null ? null : tools.number(input, "groupId", 0);
        if (groupId != null && groupId > 0) check(repository.groupMembership(groupId, user) != null, "需要先加入该学习小组");
        else groupId = null;

        long planId = repository.insertPlan(user, title, subject == null ? "" : subject, goal == null ? "" : goal, start, end, groupId);

        if (input.get("templateId") != null) {
            long templateId = tools.number(input, "templateId", 0);
            if (templateId > 0) templates.applyTasks(planId, templateId, start, end, user);
        }
        if (input.get("tasks") instanceof List<?> tasks) {
            int order = 0;
            for (Object item : tasks) {
                if (!(item instanceof Map<?, ?> raw)) continue;
                Map<String, Object> task = new LinkedHashMap<>();
                raw.forEach((key, value) -> task.put(String.valueOf(key), value));
                String taskTitle = tools.text(task, "title", 1, 100, "任务标题需要 1 到 100 个字");
                LocalDate due = task.get("dueDate") == null ? null : tools.date(task, "dueDate", null);
                repository.insertTask(planId, taskTitle, due, order++);
            }
        }
        return detail(planId, user);
    }

    public Map<String, Object> planStats(Authentication auth) {
        return stats(id(auth));
    }

    /** 计划统计：总数、完成率、今日到期、逾期、连续有进展的天数。 */
    private Map<String, Object> stats(long user) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", tools.rowNumber(repository.planCount(user), "count"));
        result.put("active", tools.rowNumber(repository.activePlanCount(user), "count"));
        result.put("completed", tools.rowNumber(repository.completedPlanCount(user), "count"));
        long totalTasks = tools.rowNumber(repository.taskCount(user), "count");
        long doneTasks = tools.rowNumber(repository.doneTaskCount(user), "count");
        result.put("totalTasks", totalTasks);
        result.put("doneTasks", doneTasks);
        result.put("rate", totalTasks == 0 ? 0 : Math.round(doneTasks * 100.0 / totalTasks));
        result.put("today", tools.rowNumber(repository.tasksDueToday(user), "count"));
        result.put("overdue", tools.rowNumber(repository.overdueTasks(user), "count"));

        List<Map<String, Object>> days = repository.completedDays(user);
        List<LocalDate> dates = new ArrayList<>();
        for (Map<String, Object> row : days) {
            LocalDate date = StudyToolService.toDate(row.get("date"));
            if (date != null) dates.add(date);
        }
        result.put("streak", tools.streak(dates, LocalDate.now()));
        return result;
    }

    public Map<String, Object> plan(Authentication auth, long planId) {
        return detail(planId, id(auth));
    }

    @Transactional
    public Map<String, Object> updatePlan(Authentication auth, long planId, Map<String, Object> input) {
        accounts.writable(auth);
        long user = id(auth);
        Map<String, Object> plan = plan(planId, user);
        String title = input.containsKey("title") ? tools.text(input, "title", 2, 60, "计划标题需要 2 到 60 个字") : String.valueOf(plan.get("title"));
        String subject = input.containsKey("subject") ? tools.optionalText(input, "subject", 40) : (String) plan.get("subject");
        String goal = input.containsKey("goal") ? tools.optionalText(input, "goal", 200) : (String) plan.get("goal");
        LocalDate start = input.containsKey("startDate") ? tools.date(input, "startDate", StudyToolService.toDate(plan.get("start_date"))) : StudyToolService.toDate(plan.get("start_date"));
        LocalDate end = input.containsKey("endDate") ? (input.get("endDate") == null ? null : tools.date(input, "endDate", null)) : StudyToolService.toDate(plan.get("end_date"));
        check(new StudyPlanPeriod(start, end).isValid(), "结束日期不能早于开始日期");
        String status = input.containsKey("status") ? String.valueOf(input.get("status")) : String.valueOf(plan.get("status"));
        check(STATUSES.contains(status), "计划状态无效");
        Long groupId = plan.get("group_id") == null ? null : tools.rowNumber(plan, "group_id");
        if (input.containsKey("groupId")) {
            groupId = input.get("groupId") == null ? null : tools.number(input, "groupId", 0);
            if (groupId != null && groupId > 0) check(repository.groupMembership(groupId, user) != null, "需要先加入该学习小组");
            else groupId = null;
        }

        repository.updatePlan(title, subject == null ? "" : subject, goal == null ? "" : goal, start, end, status, groupId, planId);
        progress.syncPlanStatus(planId);
        return detail(planId, user);
    }

    /**
     * 归档 / 取消归档。默认归档；带 {"archived": false} 时恢复成 active，
     * 恢复后由 syncPlanStatus 按任务完成情况决定它该是 active 还是 completed。
     */
    @Transactional
    public Map<String, Object> archivePlan(Authentication auth, long planId, Map<String, Object> input) {
        accounts.writable(auth);
        long user = id(auth);
        plan(planId, user);
        boolean archived = input == null || !input.containsKey("archived") || booleanFlag(input, "archived");
        repository.setPlanStatus(archived ? "archived" : "active", planId);
        if (!archived) progress.syncPlanStatus(planId);
        return detail(planId, user);
    }

    public Map<String, Object> deletePlan(Authentication auth, long planId) {
        accounts.writable(auth);
        long user = id(auth);
        plan(planId, user);
        repository.deletePlan(planId);
        return Map.of("deleted", true);
    }

    // ---------- 计划模板 ----------

    /**
     * 套用模板生成计划：模板任务按"第 N 天"落在计划开始日之后，
     * 这样同一个模板在不同起点都能生成合理的到期日。
     */
    @Transactional
    public Map<String, Object> applyTemplateEndpoint(Authentication auth, long templateId, Map<String, Object> input) {
        accounts.writable(auth);
        long user = id(auth);
        Map<String, Object> template = templates.readableTemplate(templateId, user);
        Map<String, Object> body = input == null ? new LinkedHashMap<>() : input;
        String title = body.containsKey("title") ? tools.text(body, "title", 2, 60, "计划标题需要 2 到 60 个字") : String.valueOf(template.get("title"));
        LocalDate start = body.containsKey("startDate") ? tools.date(body, "startDate", LocalDate.now()) : LocalDate.now();
        int cycle = (int) tools.number(template, "cycle_days", 7);
        LocalDate end = body.containsKey("endDate") ? tools.date(body, "endDate", null) : start.plusDays(Math.max(0, cycle - 1));
        // study_plans 的 subject/goal 都是 NOT NULL：模板里留空时要落成空串，否则整条 INSERT 会被 MySQL 拒绝。
        String subject = template.get("subject") == null ? "" : String.valueOf(template.get("subject")).trim();
        String goal = template.get("goal") == null ? "" : String.valueOf(template.get("goal"));
        Long groupId = body.get("groupId") == null ? null : tools.number(body, "groupId", 0);
        if (groupId != null && groupId > 0) check(repository.groupMembership(groupId, user) != null, "需要先加入该学习小组");
        else groupId = null;

        long planId = repository.insertTemplatePlan(user, title, subject, goal, start, end, groupId, templateId);
        templates.applyTasks(planId, templateId, start, end, user);
        repository.incrementTemplateUse(templateId);
        return detail(planId, user);
    }

}
