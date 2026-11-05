package com.studentforum.service;


import java.time.LocalDate;
import com.studentforum.repository.CheckinRepository;
import java.time.temporal.ChronoUnit;
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
 * 学习打卡：创建打卡目标、每日打卡、打卡日历、连续天数、补卡、提醒时间。
 * 积分与徽章的结算集中在 StudyRewardService，这里只负责校验和写库。
 */
@Service
public class CheckinService {
    private static final Set<String> FREQUENCIES = Set.of("daily", "weekly");

    private final CheckinRepository checkins;
    private final AccountAccessService accounts;
    private final StudyToolService tools;
    private final StudySettingsService studySettings;
    private final BadgeService badges;
    private final StudyRewardService rewards;

    public CheckinService(CheckinRepository checkins, AccountAccessService accounts, StudyToolService tools, StudySettingsService studySettings, StudyRewardService rewards, BadgeService badges) {
        this.checkins = checkins;
        this.accounts = accounts;
        this.tools = tools;
        this.studySettings = studySettings;
        this.badges = badges;
        this.rewards = rewards;
    }

    private Map<String, Object> required(Map<String, Object> row, String message) {
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
        return row;
    }

    private long id(Authentication auth) {
        return accounts.id(auth);
    }

    private Long userOrNull(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) return null;
        return Long.parseLong(auth.getName());
    }

    private void check(boolean condition, String message) {
        if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private void forbidden(String message) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }

    private long rowNumber(Map<String, Object> row, String key) {
        return tools.rowNumber(row, key);
    }

    private List<LocalDate> checkinDates(Long goalId, long userId) {
        List<Map<String, Object>> rows = goalId == null
                ? checkins.userDates(userId)
                : checkins.goalDates(goalId, userId);
        return rows.stream().map(row -> StudyToolService.toDate(row.get("checkin_date")))
                .filter(java.util.Objects::nonNull).toList();
    }

    /** 兼容 JSON 布尔与字符串布尔，避免前端传 "false" 被当成 true。 */
    private boolean flag(Object value, boolean fallback) {
        if (value == null) return fallback;
        if (value instanceof Boolean bool) return bool;
        String text = String.valueOf(value);
        return "true".equalsIgnoreCase(text) || "1".equals(text);
    }

    // ---------- 设置与统计 ----------

    public Map<String, Object> settings() {
        return studySettings.settings();
    }

    /** 打卡总览：今日打了几个目标、总天数、当前与最长连续天数、专注与积分汇总。 */
    public Map<String, Object> stats(Authentication auth) {
        long user = id(auth);
        LocalDate today = LocalDate.now();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("today", checkins.todayCount(user));
        result.put("total", checkins.totalCount(user));
        result.put("days", checkins.totalDays(user));
        result.put("streak", tools.streak(checkinDates(null, user), today));
        result.put("makeup", checkins.makeupCount(user));
        result.put("points", checkins.totalPoints(user));
        result.put("minutes", checkins.totalMinutes(user));
        result.put("activeGoals", checkins.activeGoals(user));

        List<LocalDate> dates = checkinDates(null, user);
        int longest = 0;
        LocalDate previous = null;
        for (int index = dates.size() - 1; index >= 0; index--) {
            LocalDate current = dates.get(index);
            if (previous != null && ChronoUnit.DAYS.between(previous, current) == 1) {
                longest = Math.max(longest + 1, 2);
            } else {
                longest = Math.max(longest, 1);
            }
            previous = current;
        }
        result.put("longest", longest);
        result.put("badges", badges.badgeCatalog(user));
        // 打卡提醒：今天还没打、且设了提醒时间的活跃目标，前端据此显示"该打卡了"
        result.put("pending", checkins.pendingReminders(user));
        return result;
    }

    /** 打卡日历：默认当月，最多回溯 12 个月；返回每天打了几个目标、多少分钟。 */
    public Map<String, Object> calendar(Authentication auth,
                                Integer year,
                                Integer month) {
        long user = id(auth);
        LocalDate today = LocalDate.now();
        int targetYear = year == null ? today.getYear() : year;
        int targetMonth = month == null ? today.getMonthValue() : month;
        check(targetMonth >= 1 && targetMonth <= 12 && targetYear >= 2000 && targetYear <= 2100, "月份无效");
        LocalDate start = LocalDate.of(targetYear, targetMonth, 1);
        LocalDate end = start.plusDays(start.lengthOfMonth() - 1);

        List<Map<String, Object>> days = checkins.calendar(user, start, end);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : days) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("date", String.valueOf(row.get("checkin_date")).substring(0, 10));
            item.put("today", item.get("date").equals(today.toString()));
            items.add(item);
        }

        List<LocalDate> dates = checkinDates(null, user);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("year", targetYear);
        result.put("month", targetMonth);
        result.put("start", start.toString());
        result.put("end", end.toString());
        result.put("days", items);
        result.put("checkedDays", items.size());
        result.put("minutes", items.stream().mapToLong(item -> rowNumber(item, "minutes")).sum());
        result.put("streak", tools.streak(dates, today));
        result.put("todayChecked", dates.contains(today));
        return result;
    }

    // ---------- 我的目标 ----------

    public List<Map<String, Object>> goals(Authentication auth, String status) {
        long user = id(auth);
        String filter = Set.of("active", "archived", "all").contains(status) ? status : "active";
        List<Map<String, Object>> rows = checkins.goals(user, filter);
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> goals = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> goal = new LinkedHashMap<>(row);
            goal.put("streak", tools.streak(checkinDates(rowNumber(row, "id"), user), today));
            goal.put("checkedToday", rowNumber(row, "today_count") > 0);
            // 前端据此把"今天还没打卡、且设了提醒时间"的目标排在待办区
            goal.put("pendingReminder", !Boolean.TRUE.equals(goal.get("checkedToday")) && goal.get("reminder_time") != null);
            goals.add(goal);
        }
        return goals;
    }

    /** 学生广场：别人的公开打卡目标，用来互相监督。 */
    public List<Map<String, Object>> publicGoals(Authentication auth, String subject) {
        Long me = userOrNull(auth);
        List<Map<String, Object>> rows = checkins.publicGoals(me, subject);
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> goals = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> goal = new LinkedHashMap<>(row);
            goal.put("streak", tools.streak(checkinDates(rowNumber(row, "id"), rowNumber(row, "owner_id")), today));
            goal.put("mine", me != null && rowNumber(row, "owner_id") == me);
            goals.add(goal);
        }
        return goals;
    }

    /** 打卡记录流：默认自己发起过的全部打卡，可按目标筛选。 */
    public Map<String, Object> records(Authentication auth,
                               Long goalId,
                               int page) {
        long user = id(auth);
        int offset = Math.max(0, Math.min(9999, page - 1)) * 20;
        List<Map<String, Object>> rows = checkins.records(user, goalId, offset);
        boolean hasNext = rows.size() > 20;
        return Map.of("items", hasNext ? rows.subList(0, 20) : rows, "hasNext", hasNext, "page", Math.max(1, page));
    }

    @Transactional
    public Map<String, Object> createGoal(Authentication auth, Map<String, Object> input) {
        accounts.writable(auth);
        long user = id(auth);
        String title = tools.text(input, "title", 2, 80, "目标名称需要 2 至 80 个字");
        String subject = tools.text(input, "subject", 0, 40, "科目不能超过 40 个字");
        String frequency = input.get("frequency") == null ? "daily" : String.valueOf(input.get("frequency"));
        check(FREQUENCIES.contains(frequency), "打卡频率无效");
        int targetMinutes = (int) tools.number(input, "targetMinutes", 0);
        check(targetMinutes >= 0 && targetMinutes <= StudyToolService.MAX_MINUTES, "每日目标时长需要在 0 到 720 分钟之间");

        String visibility = input.get("visibility") == null ? "private" : String.valueOf(input.get("visibility"));
        check(StudyToolService.VISIBILITIES.contains(visibility), "可见性无效");
        Long groupId = group(input, visibility, user);
        String reminder = reminder(input.get("reminderTime"));
        boolean allowMakeup = flag(input.get("allowMakeup"), true);

        long goalId = checkins.insertGoal(user, title, subject, frequency, targetMinutes, visibility, groupId, allowMakeup, reminder);
        return goal(goalId, auth);
    }

    public Map<String, Object> updateGoal(Authentication auth, long goalId, Map<String, Object> input) {
        long user = id(auth);
        Map<String, Object> old = goalForUpdate(goalId, user);
        String title = input.containsKey("title") ? tools.text(input, "title", 2, 80, "目标名称需要 2 至 80 个字") : String.valueOf(old.get("title"));
        String subject = input.containsKey("subject") ? tools.text(input, "subject", 0, 40, "科目不能超过 40 个字") : String.valueOf(old.get("subject"));
        String frequency = input.containsKey("frequency") ? String.valueOf(input.get("frequency")) : String.valueOf(old.get("frequency"));
        check(FREQUENCIES.contains(frequency), "打卡频率无效");
        int targetMinutes = input.containsKey("targetMinutes") ? (int) tools.number(input, "targetMinutes", 0) : (int) rowNumber(old, "target_minutes");
        check(targetMinutes >= 0 && targetMinutes <= StudyToolService.MAX_MINUTES, "每日目标时长需要在 0 到 720 分钟之间");
        String visibility = input.containsKey("visibility") ? String.valueOf(input.get("visibility")) : String.valueOf(old.get("visibility"));
        check(StudyToolService.VISIBILITIES.contains(visibility), "可见性无效");
        Long groupId = input.containsKey("groupId") || input.containsKey("visibility") ? group(input, visibility, user) : (old.get("group_id") == null ? null : rowNumber(old, "group_id"));
        String reminder = input.containsKey("reminderTime") ? reminder(input.get("reminderTime")) : (old.get("reminder_time") == null ? null : String.valueOf(old.get("reminder_time")));
        boolean allowMakeup = input.containsKey("allowMakeup") ? flag(input.get("allowMakeup"), true) : tools.rowFlag(old, "allow_makeup");

        checkins.updateGoal(title, subject, frequency, targetMinutes, visibility, groupId, allowMakeup, reminder, goalId);
        return goal(goalId, auth);
    }

    public Map<String, Object> archiveGoal(Authentication auth, long goalId, Map<String, Object> input) {
        long user = id(auth);
        Map<String, Object> goal = goalForUpdate(goalId, user);
        boolean archive = input == null || !input.containsKey("archived") || Boolean.TRUE.equals(input.get("archived"));
        checkins.setGoalStatus(archive ? "archived" : "active", goalId);
        return goal(goalId, auth);
    }

    @Transactional
    public void deleteGoal(Authentication auth, long goalId) {
        long user = id(auth);
        goalForUpdate(goalId, user);
        // checkins 有 ON DELETE CASCADE，历史打卡会跟着目标一起走；积分流水按项目惯例保留
        checkins.deleteGoal(goalId);
    }

    // ---------- 打卡 ----------

    /** 每日打卡。系统代打（番茄钟/计划任务）走 source 与 planId/taskId，并把关联列记下来备查。 */
    @Transactional
    public Map<String, Object> checkin(Authentication auth, Map<String, Object> input) {
        accounts.writable(auth);
        long user = id(auth);
        long goalId = tools.number(input, "goalId", 0);
        Map<String, Object> goal = required(checkins.ownedGoal(goalId, user), "打卡目标不存在");
        check(!"archived".equals(goal.get("status")), "该目标已归档，请先恢复");

        LocalDate today = LocalDate.now();
        LocalDate date = tools.date(input, "date", today);
        int duration = (int) tools.number(input, "durationMinutes", 0);
        check(duration >= 0 && duration <= StudyToolService.MAX_MINUTES, "打卡时长需要在 0 到 720 分钟之间");
        String content = tools.optionalText(input, "content", 500);
        String image = tools.image(input.get("imageUrl"));
        check(content != null || image != null || duration > 0, "请填写打卡内容、上传图片或记录时长");

        LocalDate created = StudyToolService.toDate(goal.get("created_at"));
        if (!date.isAfter(today) && created != null && date.isBefore(created)) check(false, "不能补打卡目标创建之前的日期");
        if (date.isAfter(today)) check(false, "不能给未来的日期打卡");

        long existing = checkins.goalCountOnDate(goalId, date);
        check(existing == 0, "该目标今天已经打过卡了");

        boolean makeUp = date.isBefore(today);
        if (makeUp) {
            check(studySettings.allowMakeup(), "当前不允许补卡");
            check(Boolean.TRUE.equals(goal.get("allow_makeup")) || tools.rowFlag(goal, "allow_makeup"), "这个目标没有开启补卡");
            check(ChronoUnit.DAYS.between(date, today) <= 2, "只能补最近 3 天的打卡");
            long sameDayMakeups = checkins.userCountOnDate(user, date);
            check(sameDayMakeups == 0, "这一天已经有一条补卡记录了");
        }
        int target = (int) rowNumber(goal, "target_minutes");
        check(target <= 0 || duration >= target, "本次时长还没达到目标 " + target + " 分钟");

        String subject = tools.optionalText(input, "subject", 40);
        String plan = tools.optionalText(input, "source", 10);
        Long planId = input.get("planId") == null ? null : tools.number(input, "planId", 0);
        Long taskId = input.get("taskId") == null ? null : tools.number(input, "taskId", 0);
        long checkinId = checkins.insertCheckin(user, goalId, content == null ? "" : content, duration, subject == null ? String.valueOf(goal.getOrDefault("subject", "")) : subject, image, String.valueOf(goal.get("visibility")), date, makeUp, plan == null || plan.isBlank() ? "manual" : plan, planId, taskId);

        List<LocalDate> dates = checkinDates(goalId, user);
        int streak = tools.streak(dates, today);
        Map<String, Object> settings = studySettings.settings();
        int points = studySettings.setting(settings, "checkin_points", 2);
        int bonusDays = studySettings.setting(settings, "streak_bonus_days", 7);
        if (bonusDays > 0 && streak > 0 && streak % bonusDays == 0) points += studySettings.setting(settings, "streak_bonus", 1);
        checkins.setCheckinPoints(points, checkinId);
        List<Map<String, Object>> badges = rewards.award(user, "checkin", points, "checkin", checkinId, 0);

        Map<String, Object> result = new LinkedHashMap<>(required(checkins.checkin(checkinId), "打卡记录不存在"));
        // 这里必须是可容纳 null 的浅拷贝：image_url/plan_id/task_id 为空时 Map.copyOf 会直接抛 NPE
        result.put("checkin", result.get("id") == null ? null : new LinkedHashMap<>(result));
        result.put("streak", streak);
        // points 与 pointsAwarded 是同一个值：前者方便前端统一读"本次获得积分"，后者沿用既有调用方
        result.put("points", points);
        result.put("pointsAwarded", points);
        result.put("badges", badges);
        return result;
    }

    /** 徽章图鉴：已获得的带 earned/earned_at，未获得的也要返回，前端才能显示"未点亮"的徽章。 */
    public Map<String, Object> badges(Authentication auth) {
        return Map.of("items", badges.badgeCatalog(id(auth)));
    }

    public Map<String, Object> goalDetail(Authentication auth, long goalId) {
        return goal(goalId, auth);
    }

    /** 某个目标的打卡记录，用于目标详情页的日历。 */
    public Map<String, Object> goalRecords(Authentication auth, long goalId, int days) {
        long user = id(auth);
        Map<String, Object> goal = goalForUpdate(goalId, user);
        int span = Math.max(7, Math.min(365, days));
        List<Map<String, Object>> items = checkins.goalRecords(goalId, span);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("goal", goal);
        result.put("items", items);
        result.put("streak", tools.streak(checkinDates(goalId, user), LocalDate.now()));
        return result;
    }

    // ---------- 私有工具 ----------

    private Map<String, Object> goal(long goalId, Authentication auth) {
        long user = id(auth);
        Map<String, Object> row = checkins.goalSummary(goalId);
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "打卡目标不存在");
        boolean mine = rowNumber(row, "user_id") == user;
        if (!mine && !"public".equals(row.get("visibility"))) {
            boolean shared = "group".equals(row.get("visibility")) && row.get("group_id") != null
                    && checkins.groupMembership(rowNumber(row, "group_id"), user);
            if (!shared) forbidden("这个打卡目标不对外可见");
        }
        Map<String, Object> goal = new LinkedHashMap<>(row);
        goal.put("owned", mine);
        goal.put("streak", tools.streak(checkinDates(goalId, rowNumber(row, "user_id")), LocalDate.now()));
        goal.put("checkedToday", rowNumber(row, "today_count") > 0);
        goal.put("recent", checkins.recent(goalId));
        return goal;
    }

    private Map<String, Object> goalForUpdate(long goalId, long userId) {
        Map<String, Object> row = checkins.goal(goalId);
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "打卡目标不存在");
        if (rowNumber(row, "user_id") != userId) forbidden("只能修改自己的打卡目标");
        return row;
    }

    /**
     * 解析关联学习小组：只有 visibility=group 时才需要，且必须是当前用户已加入的小组。
     * 用 group_id 关联是"小组计划/小组打卡可见性"落地的唯一方式。
     */
    private Long group(Map<String, Object> input, String visibility, long userId) {
        if (!"group".equals(visibility)) return null;
        long groupId = tools.number(input, "groupId", 0);
        if (groupId <= 0) check(false, "请选择要关联的学习小组");
        long rows = checkins.activeGroupMembership(groupId, userId);
        check(rows > 0, "只能关联自己已加入的学习小组");
        return groupId;
    }

    /** 提醒时间只接受 HH:mm，存字符串即可（不需要按服务器时区换算）。 */
    private String reminder(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) return null;
        check(text.matches("([01]\\d|2[0-3]):[0-5]\\d"), "提醒时间需要是 HH:mm 格式");
        return text;
    }
}
