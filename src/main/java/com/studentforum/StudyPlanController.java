package com.studentforum;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 学习计划：计划 CRUD、任务拆解与勾选、计划模板、计划统计。
 * 计划、任务、专注记录、打卡共用一条"做了多少"的账，勾任务会回写积分和徽章。
 */
@RestController
@RequestMapping("/api/plans")
class StudyPlanController {
    private static final Set<String> STATUSES = Set.of("active", "completed", "archived");

    private final JdbcTemplate db;
    private final ForumService service;
    private final StudyToolService tools;

    StudyPlanController(JdbcTemplate db, ForumService service, StudyToolService tools) {
        this.db = db;
        this.service = service;
        this.tools = tools;
    }

    private long id(Authentication auth) {
        return service.id(auth);
    }

    private void check(boolean condition, String message) {
        if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private boolean booleanFlag(Map<String, Object> input, String key) {
        Object value = input.get(key);
        return value instanceof Boolean flag ? flag : "true".equals(String.valueOf(value));
    }

    /** 计划归属：读自己的计划要 404 掉别人的，避免越权看到私密计划内容。 */
    private Map<String, Object> plan(long planId, long user) {
        return tools.requiredRow("SELECT * FROM study_plans WHERE id=? AND user_id=?", "学习计划不存在", planId, user);
    }

    private Map<String, Object> detail(long planId, long user) {
        Map<String, Object> plan = new LinkedHashMap<>(plan(planId, user));
        plan.put("tasks", tools.planTasks(planId));
        plan.put("progress", tools.progress(planId));
        plan.put("focusMinutes", tools.rowNumber(tools.row("SELECT COALESCE(SUM(duration_minutes),0) AS minutes FROM focus_sessions WHERE plan_id=?", planId), "minutes"));
        if (plan.get("group_id") != null) {
            Map<String, Object> group = tools.row("SELECT name FROM study_groups WHERE id=?", plan.get("group_id"));
            plan.put("groupName", group == null ? null : group.get("name"));
        } else {
            plan.put("groupName", null);
        }
        return plan;
    }

    // ---------- 计划 ----------

    @GetMapping
    Map<String, Object> plans(Authentication auth,
                              @RequestParam(required = false) String status,
                              @RequestParam(required = false) String subject) {
        long user = id(auth);
        List<Object> args = new ArrayList<>();
        args.add(user);
        StringBuilder sql = new StringBuilder("SELECT p.*,u.username AS owner_username FROM study_plans p JOIN users u ON u.id=p.user_id WHERE p.user_id=?");
        if (status != null && !status.isBlank() && !"all".equals(status)) {
            check(STATUSES.contains(status), "计划状态无效");
            sql.append(" AND p.status=?");
            args.add(status);
        }
        if (subject != null && !subject.isBlank()) {
            sql.append(" AND p.subject=?");
            args.add(subject.trim());
        }
        sql.append(" ORDER BY p.status='active' DESC,p.end_date IS NULL,p.end_date,p.id DESC LIMIT 100");
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : db.queryForList(sql.toString(), args.toArray())) {
            Map<String, Object> plan = new LinkedHashMap<>(row);
            plan.put("progress", tools.progress(tools.rowNumber(row, "id")));
            items.add(plan);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("stats", stats(user));
        result.put("subjects", tools.subjects(user));
        return result;
    }

    @PostMapping
    @Transactional
    Map<String, Object> createPlan(Authentication auth, @RequestBody Map<String, Object> input) {
        service.writable(auth);
        long user = id(auth);
        String title = tools.text(input, "title", 2, 60, "计划标题需要 2 到 60 个字");
        String subject = tools.optionalText(input, "subject", 40);
        String goal = tools.optionalText(input, "goal", 200);
        LocalDate start = tools.date(input, "startDate", LocalDate.now());
        LocalDate end = input.get("endDate") == null ? null : tools.date(input, "endDate", null);
        check(end == null || !end.isBefore(start), "结束日期不能早于开始日期");
        Long groupId = input.get("groupId") == null ? null : tools.number(input, "groupId", 0);
        if (groupId != null && groupId > 0) check(tools.row("SELECT 1 FROM study_group_members WHERE group_id=? AND user_id=?", groupId, user) != null, "需要先加入该学习小组");
        else groupId = null;

        db.update("INSERT INTO study_plans(user_id,title,subject,goal,start_date,end_date,status,group_id) VALUES(?,?,?,?,?,?,'active',?)", user, title, subject == null ? "" : subject, goal == null ? "" : goal, start, end, groupId);
        long planId = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        if (input.get("templateId") != null) {
            long templateId = tools.number(input, "templateId", 0);
            if (templateId > 0) applyTemplate(planId, templateId, start, end, user);
        }
        if (input.get("tasks") instanceof List<?> tasks) {
            int order = 0;
            for (Object item : tasks) {
                if (!(item instanceof Map<?, ?> raw)) continue;
                Map<String, Object> task = new LinkedHashMap<>();
                raw.forEach((key, value) -> task.put(String.valueOf(key), value));
                String taskTitle = tools.text(task, "title", 1, 100, "任务标题需要 1 到 100 个字");
                LocalDate due = task.get("dueDate") == null ? null : tools.date(task, "dueDate", null);
                db.update("INSERT INTO study_tasks(plan_id,title,due_date,status,sort_order) VALUES(?,?,?,'pending',?)", planId, taskTitle, due, order++);
            }
        }
        return detail(planId, user);
    }

    @GetMapping("/stats")
    Map<String, Object> planStats(Authentication auth) {
        return stats(id(auth));
    }

    /** 计划统计：总数、完成率、今日到期、逾期、连续有进展的天数。 */
    private Map<String, Object> stats(long user) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", tools.rowNumber(tools.row("SELECT COUNT(*) AS count FROM study_plans WHERE user_id=? AND status<>'archived'", user), "count"));
        result.put("active", tools.rowNumber(tools.row("SELECT COUNT(*) AS count FROM study_plans WHERE user_id=? AND status='active'", user), "count"));
        result.put("completed", tools.rowNumber(tools.row("SELECT COUNT(*) AS count FROM study_plans WHERE user_id=? AND status='completed'", user), "count"));
        long totalTasks = tools.rowNumber(tools.row("SELECT COUNT(*) AS count FROM study_tasks t JOIN study_plans p ON p.id=t.plan_id WHERE p.user_id=? AND t.parent_id IS NULL", user), "count");
        long doneTasks = tools.rowNumber(tools.row("SELECT COUNT(*) AS count FROM study_tasks t JOIN study_plans p ON p.id=t.plan_id WHERE p.user_id=? AND t.parent_id IS NULL AND t.status='done'", user), "count");
        result.put("totalTasks", totalTasks);
        result.put("doneTasks", doneTasks);
        result.put("rate", totalTasks == 0 ? 0 : Math.round(doneTasks * 100.0 / totalTasks));
        result.put("today", tools.rowNumber(tools.row("SELECT COUNT(*) AS count FROM study_tasks t JOIN study_plans p ON p.id=t.plan_id WHERE p.user_id=? AND t.status='pending' AND t.due_date=CURRENT_DATE()", user), "count"));
        result.put("overdue", tools.rowNumber(tools.row("SELECT COUNT(*) AS count FROM study_tasks t JOIN study_plans p ON p.id=t.plan_id WHERE p.user_id=? AND t.status='pending' AND t.due_date<CURRENT_DATE()", user), "count"));

        List<Map<String, Object>> days = db.queryForList("SELECT DATE(t.completed_at) AS date FROM study_tasks t JOIN study_plans p ON p.id=t.plan_id WHERE p.user_id=? AND t.completed_at IS NOT NULL GROUP BY DATE(t.completed_at) ORDER BY date DESC LIMIT 400", user);
        List<LocalDate> dates = new ArrayList<>();
        for (Map<String, Object> row : days) {
            LocalDate date = StudyToolService.toDate(row.get("date"));
            if (date != null) dates.add(date);
        }
        result.put("streak", tools.streak(dates, LocalDate.now()));
        return result;
    }

    @GetMapping("/{planId}")
    Map<String, Object> plan(Authentication auth, @PathVariable long planId) {
        return detail(planId, id(auth));
    }

    @PatchMapping("/{planId}")
    @Transactional
    Map<String, Object> updatePlan(Authentication auth, @PathVariable long planId, @RequestBody Map<String, Object> input) {
        service.writable(auth);
        long user = id(auth);
        Map<String, Object> plan = plan(planId, user);
        String title = input.containsKey("title") ? tools.text(input, "title", 2, 60, "计划标题需要 2 到 60 个字") : String.valueOf(plan.get("title"));
        String subject = input.containsKey("subject") ? tools.optionalText(input, "subject", 40) : (String) plan.get("subject");
        String goal = input.containsKey("goal") ? tools.optionalText(input, "goal", 200) : (String) plan.get("goal");
        LocalDate start = input.containsKey("startDate") ? tools.date(input, "startDate", StudyToolService.toDate(plan.get("start_date"))) : StudyToolService.toDate(plan.get("start_date"));
        LocalDate end = input.containsKey("endDate") ? (input.get("endDate") == null ? null : tools.date(input, "endDate", null)) : StudyToolService.toDate(plan.get("end_date"));
        check(end == null || start == null || !end.isBefore(start), "结束日期不能早于开始日期");
        String status = input.containsKey("status") ? String.valueOf(input.get("status")) : String.valueOf(plan.get("status"));
        check(STATUSES.contains(status), "计划状态无效");
        Long groupId = plan.get("group_id") == null ? null : tools.rowNumber(plan, "group_id");
        if (input.containsKey("groupId")) {
            groupId = input.get("groupId") == null ? null : tools.number(input, "groupId", 0);
            if (groupId != null && groupId > 0) check(tools.row("SELECT 1 FROM study_group_members WHERE group_id=? AND user_id=?", groupId, user) != null, "需要先加入该学习小组");
            else groupId = null;
        }

        db.update("UPDATE study_plans SET title=?,subject=?,goal=?,start_date=?,end_date=?,status=?,group_id=? WHERE id=?",
                title, subject == null ? "" : subject, goal == null ? "" : goal, start, end, status, groupId, planId);
        tools.syncPlanStatus(planId);
        return detail(planId, user);
    }

    /**
     * 归档 / 取消归档。默认归档；带 {"archived": false} 时恢复成 active，
     * 恢复后由 syncPlanStatus 按任务完成情况决定它该是 active 还是 completed。
     */
    @PatchMapping("/{planId}/archive")
    @Transactional
    Map<String, Object> archivePlan(Authentication auth, @PathVariable long planId, @RequestBody(required = false) Map<String, Object> input) {
        service.writable(auth);
        long user = id(auth);
        plan(planId, user);
        boolean archived = input == null || !input.containsKey("archived") || booleanFlag(input, "archived");
        db.update("UPDATE study_plans SET status=? WHERE id=?", archived ? "archived" : "active", planId);
        if (!archived) tools.syncPlanStatus(planId);
        return detail(planId, user);
    }

    @DeleteMapping("/{planId}")
    Map<String, Object> deletePlan(Authentication auth, @PathVariable long planId) {
        service.writable(auth);
        long user = id(auth);
        plan(planId, user);
        db.update("DELETE FROM study_plans WHERE id=?", planId);
        return Map.of("deleted", true);
    }

    // ---------- 任务 ----------

    @PostMapping("/{planId}/tasks")
    @Transactional
    Map<String, Object> addTask(Authentication auth, @PathVariable long planId, @RequestBody Map<String, Object> input) {
        service.writable(auth);
        long user = id(auth);
        plan(planId, user);
        String title = tools.text(input, "title", 1, 100, "任务标题需要 1 到 100 个字");
        LocalDate due = input.get("dueDate") == null ? null : tools.date(input, "dueDate", null);
        Long parentId = input.get("parentId") == null ? null : tools.number(input, "parentId", 0);
        if (parentId != null && parentId > 0) {
            Map<String, Object> parent = tools.requiredRow("SELECT id,parent_id FROM study_tasks WHERE id=? AND plan_id=?", "父任务不存在", parentId, planId);
            // 只支持一层子任务：再深一层用户自己也看不懂进度条
            check(parent.get("parent_id") == null, "子任务下不能再建子任务");
        } else parentId = null;
        long order = tools.rowNumber(tools.row("SELECT COALESCE(MAX(sort_order),-1)+1 AS next FROM study_tasks WHERE plan_id=?", planId), "next");
        db.update("INSERT INTO study_tasks(plan_id,parent_id,title,due_date,status,sort_order) VALUES(?,?,?,?,'pending',?)", planId, parentId, title, due, order);
        long taskId = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (parentId != null) tools.rollupTask(parentId);
        tools.syncPlanStatus(planId);
        return detail(planId, user);
    }

    /**
     * 勾选 / 取消勾选任务。
     * 只有"从未完成变成完成"才发积分——反复勾选刷分是这类系统最常见的漏洞。
     */
    @PatchMapping("/{planId}/tasks/{taskId}")
    @Transactional
    Map<String, Object> updateTask(Authentication auth, @PathVariable long planId, @PathVariable long taskId, @RequestBody Map<String, Object> input) {
        service.writable(auth);
        long user = id(auth);
        Map<String, Object> before = plan(planId, user);
        String statusBefore = String.valueOf(before.get("status"));
        Map<String, Object> task = tools.requiredRow("SELECT * FROM study_tasks WHERE id=? AND plan_id=?", "计划任务不存在", taskId, planId);
        long children = tools.rowNumber(tools.row("SELECT COUNT(*) AS count FROM study_tasks WHERE parent_id=?", taskId), "count");
        if (children > 0) check(!input.containsKey("status"), "该任务包含子任务，请勾选子任务来推进进度");

        if (input.containsKey("title")) db.update("UPDATE study_tasks SET title=? WHERE id=?", tools.text(input, "title", 1, 100, "任务标题需要 1 到 100 个字"), taskId);
        if (input.containsKey("dueDate")) db.update("UPDATE study_tasks SET due_date=? WHERE id=?", input.get("dueDate") == null ? null : tools.date(input, "dueDate", null), taskId);

        int points = 0;
        List<Map<String, Object>> badges = new ArrayList<>();
        Map<String, Object> checkin = null;
        if (input.containsKey("status")) {
            String status = String.valueOf(input.get("status"));
            check("pending".equals(status) || "done".equals(status), "任务状态无效");
            boolean wasDone = "done".equals(task.get("status"));
            boolean nowDone = "done".equals(status);
            if (wasDone != nowDone) {
                db.update("UPDATE study_tasks SET status=?,completed_at=? WHERE id=?", status, nowDone ? LocalDateTime.now() : null, taskId);
                if (nowDone) {
                    Map<String, Object> settings = tools.settings();
                    int reward = tools.setting(settings, "plan_task_points", 1);
                    int cap = tools.setting(settings, "plan_task_daily_cap", 10);
                    points += reward;
                    badges.addAll(tools.award(user, "plan_task", reward, "study_task", taskId, cap));
                }
                if (task.get("parent_id") != null) tools.rollupTask(tools.rowNumber(task, "parent_id"));
                if (nowDone && booleanFlag(input, "checkin")) checkin = autoCheckin(user, before, taskId);
            }
        }
        tools.syncPlanStatus(planId);

        // 这一步刚好把整个计划做完：额外给一次"完成计划"奖励，且按 point_logs 去重只发一次
        if (!"completed".equals(statusBefore) && "completed".equals(String.valueOf(plan(planId, user).get("status")))) {
            Map<String, Object> settings = tools.settings();
            int bonus = tools.setting(settings, "plan_complete_points", 10);
            boolean already = tools.row("SELECT 1 FROM point_logs WHERE user_id=? AND ref_type='study_plan' AND ref_id=?", user, planId) != null;
            if (!already && bonus > 0) {
                points += bonus;
                badges.addAll(tools.award(user, "plan_complete", bonus, "study_plan", planId, 0));
            }
        }
        Map<String, Object> result = detail(planId, user);
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
        Map<String, Object> goal = tools.row("SELECT * FROM checkin_goals WHERE user_id=? AND status='active' AND subject=? ORDER BY id LIMIT 1", user, subject);
        if (goal == null) goal = tools.row("SELECT * FROM checkin_goals WHERE user_id=? AND status='active' ORDER BY id LIMIT 1", user);
        if (goal == null) return null;
        long goalId = tools.rowNumber(goal, "id");
        long exists = tools.rowNumber(tools.row("SELECT COUNT(*) AS count FROM checkins WHERE goal_id=? AND checkin_date=CURRENT_DATE()", goalId), "count");
        if (exists > 0) return null;
        int duration = (int) tools.rowNumber(goal, "target_minutes");
        db.update("INSERT INTO checkins(user_id,goal_id,content,duration_minutes,subject,visibility,checkin_date,make_up,source,plan_id,task_id) VALUES(?,?,?,?,?,?,CURRENT_DATE(),FALSE,'plan',?,?)",
                user, goalId, "完成计划任务自动打卡", duration, subject, String.valueOf(goal.get("visibility")), plan.get("id"), taskId);
        return tools.row("SELECT * FROM checkins WHERE goal_id=? AND checkin_date=CURRENT_DATE()", goalId);
    }

    @DeleteMapping("/{planId}/tasks/{taskId}")
    @Transactional
    Map<String, Object> deleteTask(Authentication auth, @PathVariable long planId, @PathVariable long taskId) {
        service.writable(auth);
        long user = id(auth);
        plan(planId, user);
        Map<String, Object> task = tools.requiredRow("SELECT * FROM study_tasks WHERE id=? AND plan_id=?", "计划任务不存在", taskId, planId);
        db.update("DELETE FROM study_tasks WHERE id=?", taskId);
        if (task.get("parent_id") != null) tools.rollupTask(tools.rowNumber(task, "parent_id"));
        tools.syncPlanStatus(planId);
        return detail(planId, user);
    }

    // ---------- 计划模板 ----------

    @GetMapping("/templates")
    Map<String, Object> templates(Authentication auth, @RequestParam(required = false) String subject) {
        long user = id(auth);
        String filter = subject == null || subject.isBlank() ? "" : " AND subject=?";
        List<Object> args = subject == null || subject.isBlank() ? List.of() : List.of(subject.trim());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("official", db.queryForList("SELECT t.*,u.username AS owner_username FROM plan_templates t LEFT JOIN users u ON u.id=t.owner_id WHERE t.owner_id IS NULL" + filter + " ORDER BY t.id", args.toArray()));
        result.put("mine", db.queryForList("SELECT t.*,u.username AS owner_username FROM plan_templates t LEFT JOIN users u ON u.id=t.owner_id WHERE t.owner_id=?" + filter + " ORDER BY t.id DESC", prepend(user, args)));
        result.put("shared", db.queryForList("SELECT t.*,u.username AS owner_username FROM plan_templates t JOIN users u ON u.id=t.owner_id WHERE t.owner_id IS NOT NULL AND t.owner_id<>? AND t.visibility='shared'" + filter + " ORDER BY t.use_count DESC,t.id DESC LIMIT 50", prepend(user, args)));
        result.put("subjects", tools.subjects(user));
        return result;
    }

    private Object[] prepend(long user, List<Object> args) {
        List<Object> all = new ArrayList<>();
        all.add(user);
        all.addAll(args);
        return all.toArray();
    }

    /** 把模板存成我的计划模板，之后可以反复套用。 */
    @PostMapping("/templates")
    @Transactional
    Map<String, Object> createTemplate(Authentication auth, @RequestBody Map<String, Object> input) {
        service.writable(auth);
        long user = id(auth);
        String title = tools.text(input, "title", 2, 60, "模板名称需要 2 到 60 个字");
        String subject = tools.optionalText(input, "subject", 40);
        String goal = tools.optionalText(input, "goal", 200);
        int cycle = (int) tools.number(input, "cycleDays", 7);
        check(cycle >= 1 && cycle <= 365, "周期需要在 1 到 365 天之间");
        String tasks = templateTasks(input);
        String visibility = input.get("visibility") == null ? "shared" : String.valueOf(input.get("visibility"));
        check("shared".equals(visibility) || "private".equals(visibility), "模板可见性无效");
        db.update("INSERT INTO plan_templates(owner_id,title,subject,goal,cycle_days,tasks,visibility) VALUES(?,?,?,?,?,?,?)", user, title, subject == null ? "" : subject, goal == null ? "" : goal, cycle, tasks, visibility);
        long templateId = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return tools.requiredRow("SELECT * FROM plan_templates WHERE id=?", "模板不存在", templateId);
    }

    /** 把自己某个计划直接存成模板（用户最常用的入口）。 */
    @PostMapping("/{planId}/template")
    @Transactional
    Map<String, Object> templateFromPlan(Authentication auth, @PathVariable long planId, @RequestBody(required = false) Map<String, Object> input) {
        service.writable(auth);
        long user = id(auth);
        Map<String, Object> plan = plan(planId, user);
        Map<String, Object> body = input == null ? new LinkedHashMap<>() : input;
        String title = body.containsKey("title") ? tools.text(body, "title", 2, 60, "模板名称需要 2 到 60 个字") : String.valueOf(plan.get("title"));
        int cycle = (int) tools.number(body, "cycleDays", 7);
        check(cycle >= 1 && cycle <= 365, "周期需要在 1 到 365 天之间");
        String visibility = body.get("visibility") == null ? "shared" : String.valueOf(body.get("visibility"));
        check("shared".equals(visibility) || "private".equals(visibility), "模板可见性无效");
        List<Map<String, Object>> tasks = tools.planTasks(planId);
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> task : tasks) {
            if (task.get("parent_id") != null) continue;
            StringBuilder line = new StringBuilder(String.valueOf(task.get("title")));
            if (task.get("due_date") != null) {
                LocalDate due = StudyToolService.toDate(task.get("due_date"));
                LocalDate start = StudyToolService.toDate(plan.get("start_date"));
                if (due != null && start != null) line.append("|第 ").append(Math.max(1, ChronoUnit.DAYS.between(start, due) + 1)).append(" 天");
            }
            lines.add(line.toString());
        }
        check(!lines.isEmpty(), "这个计划还没有任务，无法存成模板");
        long ownerId = user;
        String closed = String.join("\n", lines);
        db.update("INSERT INTO plan_templates(owner_id,title,subject,goal,cycle_days,tasks,visibility) VALUES(?,?,?,?,?,?,?)", ownerId, title, String.valueOf(plan.get("subject")), String.valueOf(plan.get("goal")), cycle, closed, visibility);
        long templateId = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return tools.requiredRow("SELECT * FROM plan_templates WHERE id=?", "模板不存在", templateId);
    }

    /**
     * 套用模板生成计划：模板任务按"第 N 天"落在计划开始日之后，
     * 这样同一个模板在不同起点都能生成合理的到期日。
     */
    @PostMapping("/templates/{templateId}/apply")
    @Transactional
    Map<String, Object> applyTemplateEndpoint(Authentication auth, @PathVariable long templateId, @RequestBody(required = false) Map<String, Object> input) {
        service.writable(auth);
        long user = id(auth);
        Map<String, Object> template = tools.requiredRow("SELECT * FROM plan_templates WHERE id=?", "模板不存在", templateId);
        check(template.get("owner_id") == null || tools.rowNumber(template, "owner_id") == user || "shared".equals(template.get("visibility")), "这个模板没有公开");
        Map<String, Object> body = input == null ? new LinkedHashMap<>() : input;
        String title = body.containsKey("title") ? tools.text(body, "title", 2, 60, "计划标题需要 2 到 60 个字") : String.valueOf(template.get("title"));
        LocalDate start = body.containsKey("startDate") ? tools.date(body, "startDate", LocalDate.now()) : LocalDate.now();
        int cycle = (int) tools.number(template, "cycle_days", 7);
        LocalDate end = body.containsKey("endDate") ? tools.date(body, "endDate", null) : start.plusDays(Math.max(0, cycle - 1));
        // study_plans 的 subject/goal 都是 NOT NULL：模板里留空时要落成空串，否则整条 INSERT 会被 MySQL 拒绝。
        String subject = template.get("subject") == null ? "" : String.valueOf(template.get("subject")).trim();
        String goal = template.get("goal") == null ? "" : String.valueOf(template.get("goal"));
        Long groupId = body.get("groupId") == null ? null : tools.number(body, "groupId", 0);
        if (groupId != null && groupId > 0) check(tools.row("SELECT 1 FROM study_group_members WHERE group_id=? AND user_id=?", groupId, user) != null, "需要先加入该学习小组");
        else groupId = null;

        db.update("INSERT INTO study_plans(user_id,title,subject,goal,start_date,end_date,status,group_id,template_id) VALUES(?,?,?,?,?,?,'active',?,?)", user, title, subject, goal, start, end, groupId, templateId);
        long planId = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        applyTemplate(planId, templateId, start, end, user);
        db.update("UPDATE plan_templates SET use_count=use_count+1 WHERE id=?", templateId);
        return detail(planId, user);
    }

    @DeleteMapping("/templates/{templateId}")
    Map<String, Object> deleteTemplate(Authentication auth, @PathVariable long templateId) {
        service.writable(auth);
        long user = id(auth);
        Map<String, Object> template = tools.requiredRow("SELECT * FROM plan_templates WHERE id=?", "模板不存在", templateId);
        if (template.get("owner_id") == null || tools.rowNumber(template, "owner_id") != user) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能删除自己的模板");
        db.update("DELETE FROM plan_templates WHERE id=?", templateId);
        return Map.of("deleted", true);
    }

    /** 模板任务文本格式：每行一条，可选 `|第 N 天` 指定相对到期日。 */
    private void applyTemplate(long planId, long templateId, LocalDate start, LocalDate end, long user) {
        Map<String, Object> template = tools.requiredRow("SELECT * FROM plan_templates WHERE id=?", "模板不存在", templateId);
        String block = template.get("tasks") == null ? "" : String.valueOf(template.get("tasks"));
        int order = 0;
        for (String line : block.split("\\r?\\n")) {
            String value = line.trim();
            if (value.isEmpty()) continue;
            int day = 0;
            int split = value.indexOf("|第 ");
            if (split > 0) {
                String tail = value.substring(split + 2).replace("天", "").trim();
                try {
                    day = Integer.parseInt(tail);
                } catch (NumberFormatException ignored) {
                    day = 0;
                }
                value = value.substring(0, split).trim();
            }
            if (value.isEmpty()) continue;
            LocalDate due = day > 0 ? start.plusDays(day - 1L) : null;
            if (due != null && end != null && due.isAfter(end)) due = end;
            db.update("INSERT INTO study_tasks(plan_id,title,due_date,status,sort_order) VALUES(?,?,?,'pending',?)", planId, value.length() > 100 ? value.substring(0, 100) : value, due, order++);
        }
    }

    private String templateTasks(Map<String, Object> input) {
        Object raw = input.get("tasks");
        if (raw instanceof List<?> list) {
            List<String> lines = new ArrayList<>();
            for (Object item : list) {
                if (item == null) continue;
                String value = String.valueOf(item).trim();
                if (!value.isEmpty()) lines.add(value.length() > 100 ? value.substring(0, 100) : value);
            }
            check(!lines.isEmpty(), "模板至少需要一条任务");
            return String.join("\n", lines);
        }
        String text = tools.text(input, "tasks", 1, 2000, "模板至少需要一条任务");
        return text;
    }
}
