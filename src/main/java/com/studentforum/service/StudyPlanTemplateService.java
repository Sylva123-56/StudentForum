package com.studentforum.service;

import com.studentforum.repository.StudyPlanRepository;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 计划模板的查询、保存和任务展开；生成计划的事务由 StudyPlanService 编排。 */
@Service
public class StudyPlanTemplateService {
    private final StudyPlanRepository repository;
    private final AccountAccessService accounts;
    private final StudyToolService tools;
    private final StudyPreferencesService preferences;
    private final StudyPlanProgressService progress;

    public StudyPlanTemplateService(StudyPlanRepository repository, AccountAccessService accounts,
                                    StudyToolService tools, StudyPreferencesService preferences,
                                    StudyPlanProgressService progress) {
        this.repository = repository;
        this.accounts = accounts;
        this.tools = tools;
        this.preferences = preferences;
        this.progress = progress;
    }

    private void check(boolean condition, String message) {
        if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private Map<String, Object> required(Map<String, Object> row, String message) {
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
        return row;
    }

    Map<String, Object> readableTemplate(long templateId, long user) {
        Map<String, Object> template = required(repository.findTemplate(templateId), "模板不存在");
        check(template.get("owner_id") == null || tools.rowNumber(template, "owner_id") == user
                || "shared".equals(template.get("visibility")), "这个模板没有公开");
        return template;
    }

    public Map<String, Object> templates(Authentication auth, String subject) {
        long user = accounts.id(auth);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("official", repository.officialTemplates(subject));
        result.put("mine", repository.ownedTemplates(user, subject));
        result.put("shared", repository.sharedTemplates(user, subject));
        result.put("subjects", preferences.subjects(user));
        return result;
    }

    /** 把模板存成我的计划模板，之后可以反复套用。 */
    @Transactional
    public Map<String, Object> createTemplate(Authentication auth, Map<String, Object> input) {
        accounts.writable(auth);
        long user = accounts.id(auth);
        String title = tools.text(input, "title", 2, 60, "模板名称需要 2 到 60 个字");
        String subject = tools.optionalText(input, "subject", 40);
        String goal = tools.optionalText(input, "goal", 200);
        int cycle = (int) tools.number(input, "cycleDays", 7);
        check(cycle >= 1 && cycle <= 365, "周期需要在 1 到 365 天之间");
        String tasks = templateTasks(input);
        String visibility = input.get("visibility") == null ? "shared" : String.valueOf(input.get("visibility"));
        check("shared".equals(visibility) || "private".equals(visibility), "模板可见性无效");
        long templateId = repository.insertTemplate(user, title, subject == null ? "" : subject, goal == null ? "" : goal, cycle, tasks, visibility);
        return required(repository.findTemplate(templateId), "模板不存在");
    }

    /** 把自己某个计划直接存成模板（用户最常用的入口）。 */
    @Transactional
    public Map<String, Object> templateFromPlan(Authentication auth, long planId, Map<String, Object> input) {
        accounts.writable(auth);
        long user = accounts.id(auth);
        Map<String, Object> plan = required(repository.findOwnedPlan(planId, user), "学习计划不存在");
        Map<String, Object> body = input == null ? new LinkedHashMap<>() : input;
        String title = body.containsKey("title") ? tools.text(body, "title", 2, 60, "模板名称需要 2 到 60 个字") : String.valueOf(plan.get("title"));
        int cycle = (int) tools.number(body, "cycleDays", 7);
        check(cycle >= 1 && cycle <= 365, "周期需要在 1 到 365 天之间");
        String visibility = body.get("visibility") == null ? "shared" : String.valueOf(body.get("visibility"));
        check("shared".equals(visibility) || "private".equals(visibility), "模板可见性无效");
        List<Map<String, Object>> tasks = progress.planTasks(planId);
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
        long templateId = repository.insertTemplate(ownerId, title, String.valueOf(plan.get("subject")), String.valueOf(plan.get("goal")), cycle, closed, visibility);
        return required(repository.findTemplate(templateId), "模板不存在");
    }

    public Map<String, Object> deleteTemplate(Authentication auth, long templateId) {
        accounts.writable(auth);
        long user = accounts.id(auth);
        Map<String, Object> template = required(repository.findTemplate(templateId), "模板不存在");
        if (template.get("owner_id") == null || tools.rowNumber(template, "owner_id") != user) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能删除自己的模板");
        repository.deleteTemplate(templateId);
        return Map.of("deleted", true);
    }

    /** 模板任务文本格式：每行一条，可选 `|第 N 天` 指定相对到期日。 */
    void applyTasks(long planId, long templateId, LocalDate start, LocalDate end, long user) {
        Map<String, Object> template = readableTemplate(templateId, user);
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
            repository.insertTask(planId, value.length() > 100 ? value.substring(0, 100) : value, due, order++);
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
