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
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 学习工具的共用逻辑：打卡连续天数、积分规则、徽章、自习室权限。
 * 三个控制器（打卡 / 番茄钟 / 学习计划）和自习室控制器都注入它，避免连续天数这类
 * 需要跨表计算的逻辑在每个控制器里各写一遍。
 */
@Service
class StudyToolService {
    /** 打卡有意义的时长上下限：0 表示只记"我来打过卡"，不校准时长。 */
    static final int MAX_MINUTES = 720;
    private static final List<String> BADGES = List.of(
            "checkin_first", "checkin_streak_3", "checkin_streak_7", "checkin_streak_30",
            "focus_first", "focus_10h", "focus_50h",
            "plan_first", "plan_5", "plan_complete", "room_host");

    private final JdbcTemplate db;
    private final ForumService service;
    private final ForumMapper mapper;

    StudyToolService(JdbcTemplate db, ForumService service, ForumMapper mapper) {
        this.db = db;
        this.service = service;
        this.mapper = mapper;
    }

    // ---------- 打卡设置 ----------

    /** 单行配置表，缺失或字段为空时按默认值兜底，保证后台没配过也能正常发积分。 */
    Map<String, Object> settings() {
        List<Map<String, Object>> rows = db.queryForList("SELECT * FROM checkin_settings WHERE id=1");
        Map<String, Object> row = rows.isEmpty() ? new LinkedHashMap<>() : new LinkedHashMap<>(rows.getFirst());
        row.putIfAbsent("checkin_points", 2);
        row.putIfAbsent("streak_bonus", 1);
        row.putIfAbsent("streak_bonus_days", 7);
        row.putIfAbsent("focus_points", 1);
        row.putIfAbsent("focus_daily_cap", 10);
        row.putIfAbsent("plan_task_points", 1);
        row.putIfAbsent("plan_task_daily_cap", 10);
        row.putIfAbsent("plan_complete_points", 10);
        row.putIfAbsent("allow_makeup", Boolean.TRUE);
        return row;
    }

    int setting(Map<String, Object> settings, String key, int fallback) {
        Object value = settings.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    boolean allowMakeup() {
        Object value = settings().get("allow_makeup");
        if (value instanceof Boolean flag) return flag;
        return value instanceof Number number ? number.intValue() != 0 : true;
    }

    // ---------- 输入工具 ----------

    long number(Map<String, Object> input, String key, long fallback) {
        Object value = input == null ? null : input.get(key);
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String text && text.trim().matches("-?\\d+")) return Long.parseLong(text.trim());
        return fallback;
    }

    String text(Map<String, Object> input, String key, int min, int max, String message) {
        Object value = input == null ? null : input.get(key);
        String result = value == null ? "" : String.valueOf(value).trim();
        if (result.length() < min || result.length() > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        if (result.contains("赌博") || result.contains("色情广告") || result.contains("代写论文")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内容包含不允许的词语");
        return result;
    }

    /** 可空的短文本：只做长度上限校验，空串一律归一成 null，避免库里出现 '' 和 NULL 两种空。 */
    String optionalText(Map<String, Object> input, String key, int max) {
        Object value = input == null ? null : input.get(key);
        String result = value == null ? "" : String.valueOf(value).trim();
        if (result.length() > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内容过长");
        return result.isEmpty() ? null : result;
    }

    /** 复用 ForumService.image 的校验（必须是我们自己上传出来的 /uploads/<uuid>.<ext>）。 */
    String image(Object value) {
        return service.image(value);
    }

    LocalDate date(Map<String, Object> input, String key, LocalDate fallback) {
        Object value = input == null ? null : input.get(key);
        if (value == null || String.valueOf(value).isBlank()) return fallback;
        try {
            return LocalDate.parse(String.valueOf(value).trim());
        } catch (RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "日期格式无效");
        }
    }

    Map<String, Object> row(String sql, Object... args) {
        return db.queryForList(sql, args).stream().findFirst().orElse(null);
    }

    Map<String, Object> requiredRow(String sql, String message, Object... args) {
        Map<String, Object> row = row(sql, args);
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
        return row;
    }

    long rowNumber(Map<String, Object> row, String key) {
        // row 可能来自 tools.row(...)：查不到行时它返回 null，这里统一当 0 处理
        Object value = row == null ? null : row.get(key);
        return value instanceof Number number ? number.longValue() : 0;
    }

    boolean rowFlag(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        if (value instanceof Boolean flag) return flag;
        return value instanceof Number number && number.intValue() != 0;
    }

    // ---------- 连续打卡 ----------

    /**
     * 连续打卡天数：从今天（今天还没打卡时从昨天）往前数，遇到断档就停。
     * 补卡补出来的日期同样算数，所以这里只看日期集合，不看打卡方式。
     */
    int streak(List<LocalDate> dates, LocalDate today) {
        if (dates == null || dates.isEmpty()) return 0;
        List<LocalDate> sorted = new ArrayList<>(dates);
        sorted.sort((left, right) -> right.compareTo(left));
        LocalDate first = sorted.getFirst();
        if (ChronoUnit.DAYS.between(first, today) > 1) return 0;
        int count = 1;
        LocalDate previous = first;
        for (int index = 1; index < sorted.size(); index++) {
            LocalDate current = sorted.get(index);
            if (ChronoUnit.DAYS.between(current, previous) == 1) {
                count++;
                previous = current;
            } else if (current.equals(previous)) {
                continue;
            } else {
                break;
            }
        }
        return count;
    }

    /** 取某个目标/某个用户打过卡的日期，最新的在前。 */
    List<LocalDate> checkinDates(Long goalId, long userId) {
        return goalId == null
                ? db.queryForList("SELECT DISTINCT checkin_date FROM checkins WHERE user_id=? ORDER BY checkin_date DESC LIMIT 400", userId)
                        .stream().map(row -> toDate(row.get("checkin_date"))).filter(java.util.Objects::nonNull).toList()
                : db.queryForList("SELECT checkin_date FROM checkins WHERE goal_id=? AND user_id=? ORDER BY checkin_date DESC LIMIT 400", goalId, userId)
                        .stream().map(row -> toDate(row.get("checkin_date"))).filter(java.util.Objects::nonNull).toList();
    }

    static LocalDate toDate(Object value) {
        if (value == null) return null;
        if (value instanceof java.sql.Date date) return date.toLocalDate();
        if (value instanceof java.time.LocalDate date) return date;
        if (value instanceof java.sql.Timestamp stamp) return stamp.toLocalDateTime().toLocalDate();
        if (value instanceof java.util.Date date) return new java.sql.Date(date.getTime()).toLocalDate();
        return LocalDate.parse(String.valueOf(value).substring(0, 10));
    }

    /** 打卡总天数（按日期去重，同一天打多个目标只算一天）。 */
    long totalDays(long userId) {
        Long days = db.queryForObject("SELECT COUNT(DISTINCT checkin_date) FROM checkins WHERE user_id=?", Long.class, userId);
        return days == null ? 0 : days;
    }

    // ---------- 积分与徽章 ----------

    /**
     * 发积分并顺手结算徽章。
     * dailyCap 传 0 表示不限次（徽章、计划完成奖这类一次性奖励用它）。
     */
    List<Map<String, Object>> award(long userId, String action, int points, String refType, Long refId, int dailyCap) {
        if (points > 0) service.reward(userId, action, points, refType, refId, dailyCap);
        return evaluateBadges(userId);
    }

    /**
     * 徽章结算：先算出用户当前达到的各项指标，再一次 INSERT IGNORE 全部候选徽章，
     * 然后只返回"这次真的插进去了"的那几枚（行数为 1 即为新获得）。
     */
    List<Map<String, Object>> evaluateBadges(long userId) {
        int bestStreak = 0;
        LocalDate today = LocalDate.now();
        for (Map<String, Object> goal : db.queryForList("SELECT id FROM checkin_goals WHERE user_id=?", userId)) {
            bestStreak = Math.max(bestStreak, streak(checkinDates(rowNumber(goal, "id"), userId), today));
        }
        long checkinCount = count("SELECT COUNT(*) FROM checkins WHERE user_id=?", userId);
        long focusMinutes = count("SELECT COALESCE(SUM(duration_minutes),0) FROM focus_sessions WHERE user_id=?", userId);
        long focusSessions = count("SELECT COUNT(*) FROM focus_sessions WHERE user_id=?", userId);
        long plans = count("SELECT COUNT(*) FROM study_plans WHERE user_id=?", userId);
        long completedPlans = count("SELECT COUNT(*) FROM study_plans WHERE user_id=? AND status='completed'", userId);
        long hostedRooms = count("SELECT COUNT(*) FROM study_rooms WHERE owner_id=?", userId);

        List<Map<String, Object>> earned = new ArrayList<>();
        add(earned, userId, checkinCount >= 1, "checkin_first", "第一次打卡", "完成第一次学习打卡");
        add(earned, userId, bestStreak >= 3, "checkin_streak_3", "三日不辍", "连续打卡 3 天");
        add(earned, userId, bestStreak >= 7, "checkin_streak_7", "七日之约", "连续打卡 7 天");
        add(earned, userId, bestStreak >= 30, "checkin_streak_30", "月度自律", "连续打卡 30 天");
        add(earned, userId, focusSessions >= 1, "focus_first", "开始专注", "完成第一次番茄钟");
        add(earned, userId, focusMinutes >= 600, "focus_10h", "专注十小时", "累计专注满 10 小时");
        add(earned, userId, focusMinutes >= 3000, "focus_50h", "专注五十小时", "累计专注满 50 小时");
        add(earned, userId, plans >= 1, "plan_first", "计划开局", "创建第一个学习计划");
        add(earned, userId, plans >= 5, "plan_5", "计划达人", "累计创建 5 个学习计划");
        add(earned, userId, completedPlans >= 1, "plan_complete", "说到做到", "完成一个完整的学习计划");
        add(earned, userId, hostedRooms >= 1, "room_host", "自习室主理人", "创建一间自习室");
        return earned;
    }

    private void add(List<Map<String, Object>> earned, long userId, boolean condition, String code, String name, String detail) {
        if (!condition) return;
        if (db.update("INSERT IGNORE INTO user_badges(user_id,code,name,detail) VALUES(?,?,?,?)", userId, code, name, detail) == 0) return;
        Map<String, Object> badge = new LinkedHashMap<>();
        badge.put("code", code);
        badge.put("name", name);
        badge.put("detail", detail);
        earned.add(badge);
        mapper.notifyUser(userId, "badge", "获得新徽章：" + name, null);
    }

    private long count(String sql, Object... args) {
        Long value = db.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    /** 全部徽章目录（含未获得的），前端拿来渲染"还差什么"。 */
    List<Map<String, Object>> badgeCatalog(long userId) {
        List<Map<String, Object>> owned = db.queryForList("SELECT code,earned_at FROM user_badges WHERE user_id=?", userId);
        Map<String, Object> earned = new LinkedHashMap<>();
        for (Map<String, Object> row : owned) earned.put(String.valueOf(row.get("code")), row.get("earned_at"));
        List<Map<String, Object>> catalog = new ArrayList<>();
        for (String code : BADGES) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", code);
            item.put("name", badgeName(code));
            item.put("detail", badgeDetail(code));
            item.put("earned", earned.containsKey(code));
            item.put("earned_at", earned.get(code));
            catalog.add(item);
        }
        return catalog;
    }

    private String badgeName(String code) {
        return switch (code) {
            case "checkin_first" -> "第一次打卡";
            case "checkin_streak_3" -> "三日不辍";
            case "checkin_streak_7" -> "七日之约";
            case "checkin_streak_30" -> "月度自律";
            case "focus_first" -> "开始专注";
            case "focus_10h" -> "专注十小时";
            case "focus_50h" -> "专注五十小时";
            case "plan_first" -> "计划开局";
            case "plan_5" -> "计划达人";
            case "plan_complete" -> "说到做到";
            case "room_host" -> "自习室主理人";
            default -> "成长徽章";
        };
    }

    private String badgeDetail(String code) {
        return switch (code) {
            case "checkin_first" -> "完成第一次学习打卡";
            case "checkin_streak_3" -> "连续打卡 3 天";
            case "checkin_streak_7" -> "连续打卡 7 天";
            case "checkin_streak_30" -> "连续打卡 30 天";
            case "focus_first" -> "完成第一次番茄钟";
            case "focus_10h" -> "累计专注满 10 小时";
            case "focus_50h" -> "累计专注满 50 小时";
            case "plan_first" -> "创建第一个学习计划";
            case "plan_5" -> "累计创建 5 个学习计划";
            case "plan_complete" -> "完成一个完整的学习计划";
            case "room_host" -> "创建一间自习室";
            default -> "";
        };
    }

    // ---------- 计划进度 ----------

    /**
     * 计划进度：分母只数顶层任务（parent_id IS NULL），
     * 子任务只是"把这件事拆小"的清单，重复计入会让百分比永远到不了 100%。
     */
    Map<String, Object> progress(long planId) {
        List<Map<String, Object>> rows = db.queryForList("SELECT COUNT(*) AS total,COALESCE(SUM(status='done'),0) AS done,COALESCE(SUM(status='done' AND DATE(completed_at)=CURRENT_DATE()),0) AS done_today,COALESCE(SUM(status='pending' AND due_date<CURRENT_DATE()),0) AS overdue FROM study_tasks WHERE plan_id=? AND parent_id IS NULL", planId);
        Map<String, Object> row = rows.isEmpty() ? new LinkedHashMap<>() : rows.getFirst();
        long total = rowNumber(row, "total"), done = rowNumber(row, "done");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("done", done);
        result.put("percent", total == 0 ? 0 : Math.round(done * 100.0 / total));
        result.put("doneToday", rowNumber(row, "done_today"));
        result.put("overdue", rowNumber(row, "overdue"));
        return result;
    }

    /** 计划下全部任务（含子任务的 child_count），详情页和模板复制都靠它。 */
    List<Map<String, Object>> planTasks(long planId) {
        return db.queryForList("SELECT t.*,(SELECT COUNT(*) FROM study_tasks c WHERE c.parent_id=t.id) AS child_count FROM study_tasks t WHERE t.plan_id=? ORDER BY t.parent_id IS NULL DESC,t.sort_order,t.id", planId);
    }

    /**
     * 有子任务的父任务不直接勾选：它的完成状态由子任务推导，
     * 否则用户勾一下父任务就能把整块进度刷满，进度条就失去意义了。
     */
    void rollupTask(long taskId) {
        Map<String, Object> task = row("SELECT id,parent_id FROM study_tasks WHERE id=?", taskId);
        if (task == null || task.get("parent_id") != null) return;
        long children = count("SELECT COUNT(*) AS count FROM study_tasks WHERE parent_id=?", taskId);
        if (children == 0) return;
        long done = count("SELECT COUNT(*) AS count FROM study_tasks WHERE parent_id=? AND status='done'", taskId);
        db.update("UPDATE study_tasks SET status=?,completed_at=? WHERE id=?", done == 0 ? "pending" : "done", done == 0 ? null : LocalDateTime.now(), taskId);
    }

    /** 计划里的任务全勾完就自动置为 completed；取消勾选时再退回 active。 */
    void syncPlanStatus(long planId) {
        Map<String, Object> plan = row("SELECT status FROM study_plans WHERE id=?", planId);
        if (plan == null) return;
        String status = String.valueOf(plan.get("status"));
        if ("archived".equals(status)) return;
        Map<String, Object> progress = progress(planId);
        long total = rowNumber(progress, "total"), done = rowNumber(progress, "done");
        if (total > 0 && done == total && !"completed".equals(status)) db.update("UPDATE study_plans SET status='completed' WHERE id=?", planId);
        if (done < total && "completed".equals(status)) db.update("UPDATE study_plans SET status='active' WHERE id=?", planId);
    }

    // ---------- 自习室 ----------

    Map<String, Object> room(long roomId) {
        return requiredRow("SELECT r.*,u.username AS owner_username,(SELECT COUNT(*) FROM study_room_members m WHERE m.room_id=r.id) AS online_members FROM study_rooms r JOIN users u ON u.id=r.owner_id WHERE r.id=?", "自习室不存在", roomId);
    }

    /**
     * 自习室可见性：公开房间人人可看；私密房间只有房主、成员，以及关联学习小组的成员能看。
     * 排行榜/成员榜这类需要匿名的接口不走这里（它们在 SQL 里直接过滤 visibility='public'）。
     */
    void requireRoomView(Map<String, Object> room, Long userId) {
        boolean member = userId != null && row("SELECT id FROM study_room_members WHERE room_id=? AND user_id=?", rowNumber(room, "id"), userId) != null;
        boolean owner = userId != null && rowNumber(room, "owner_id") == userId;
        if ("public".equals(room.get("visibility")) || member || owner) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "这是私密自习室，仅成员可以查看");
    }

    void requireRoomOwner(Map<String, Object> room, long userId) {
        if (rowNumber(room, "owner_id") != userId) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅自习室创建者可以操作");
    }

    /** 总时长/成员数每次变化后重算，只信 focus_sessions 与成员表这两处事实。 */
    void refreshRoomTotals(long roomId) {
        db.update("UPDATE study_rooms SET member_count=(SELECT COUNT(*) FROM study_room_members WHERE room_id=?),focus_minutes=(SELECT COALESCE(SUM(duration_minutes),0) FROM focus_sessions WHERE room_id=?) WHERE id=?", roomId, roomId, roomId);
    }

    /** 按可见性取用户的"学习小组"候选，供打卡目标/自习室关联小组时选择。 */
    List<Map<String, Object>> myGroups(long userId) {
        return db.queryForList("SELECT g.id,g.name,g.subject FROM study_group_members m JOIN study_groups g ON g.id=m.group_id WHERE m.user_id=? AND g.status='active' ORDER BY g.name", userId);
    }

    /** 默认学习科目候选：用户资料里填的偏好 + 学习小组里出现过的科目。 */
    List<String> subjects(long userId) {
        List<String> subjects = new ArrayList<>();
        Map<String, Object> profile = row("SELECT subject_preference FROM users WHERE id=?", userId);
        if (profile != null && profile.get("subject_preference") != null) {
            for (String part : String.valueOf(profile.get("subject_preference")).split("[,，、/]")) {
                String value = part.trim();
                if (!value.isEmpty() && !subjects.contains(value)) subjects.add(value);
            }
        }
        for (Map<String, Object> group : myGroups(userId)) {
            String subject = group.get("subject") == null ? "" : String.valueOf(group.get("subject")).trim();
            if (!subject.isEmpty() && !subjects.contains(subject)) subjects.add(subject);
        }
        return subjects.size() > 20 ? subjects.subList(0, 20) : subjects;
    }

    static final Set<String> VISIBILITIES = Set.of("public", "private", "group");
}
