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
 * 番茄钟：记录专注、专注统计、专注排行。
 * 专注记录是"自习室排行"和"与计划/打卡联动"的唯一数据源，所以时长全部落在这张表里。
 */
@RestController
@RequestMapping("/api/focus")
class FocusController {
    /** 一次专注最长 12 小时：再长也不像是真实计时，多半是前端没停表。 */
    private static final int MAX_MINUTES = 720;

    private final JdbcTemplate db;
    private final ForumService service;
    private final StudyToolService tools;

    FocusController(JdbcTemplate db, ForumService service, StudyToolService tools) {
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

    private static LocalDate toDate(Object value) {
        return StudyToolService.toDate(value);
    }

    // ---------- 记录 ----------

    /**
     * 结束一次专注。
     * durationMinutes 是前端倒计时得出的实际专注分钟数；startedAt/endedAt 也接受前端传入的真实起止时间，
     * 不传就按"现在结束、往前推 durationMinutes"补齐，这样一个接口既能收番茄钟也能收自习室的离线计时。
     */
    @PostMapping("/sessions")
    @Transactional
    Map<String, Object> createSession(Authentication auth, @RequestBody Map<String, Object> input) {
        service.writable(auth);
        long user = id(auth);
        int duration = (int) tools.number(input, "durationMinutes", 0);
        check(duration >= 1 && duration <= MAX_MINUTES, "专注时长需要在 1 到 720 分钟之间");

        Long roomId = input.get("roomId") == null ? null : tools.number(input, "roomId", 0);
        if (roomId != null && roomId > 0) {
            Map<String, Object> room = tools.requiredRow("SELECT * FROM study_rooms WHERE id=?", "自习室不存在", roomId);
            check("active".equals(String.valueOf(room.get("status"))), "自习室已关闭");
            tools.requireRoomView(room, user);
            // 在自习室里开始专注就等于加入自习室，否则时长累加会落空、房间榜也看不到自己
            if (db.queryForList("SELECT id FROM study_room_members WHERE room_id=? AND user_id=?", roomId, user).isEmpty()) {
                db.update("INSERT IGNORE INTO study_room_members(room_id,user_id,role,total_minutes) VALUES(?,?,'member',0)", roomId, user);
                tools.refreshRoomTotals(roomId);
            }
        } else roomId = null;

        Long planId = input.get("planId") == null ? null : tools.number(input, "planId", 0);
        Long taskId = input.get("taskId") == null ? null : tools.number(input, "taskId", 0);
        if (planId != null && planId > 0) tools.requiredRow("SELECT id FROM study_plans WHERE id=? AND user_id=?", "学习计划不存在", planId, user);
        else planId = null;
        if (taskId != null && taskId > 0) tools.requiredRow("SELECT t.id FROM study_tasks t JOIN study_plans p ON p.id=t.plan_id WHERE t.id=? AND p.user_id=?", "计划任务不存在", taskId, user);
        else taskId = null;

        LocalDateTime endedAt = input.get("endedAt") == null ? LocalDateTime.now() : parseDateTime(input.get("endedAt"));
        LocalDateTime startedAt = input.get("startedAt") == null ? endedAt.minusMinutes(duration) : parseDateTime(input.get("startedAt"));
        check(!startedAt.isAfter(endedAt), "开始时间不能晚于结束时间");
        // 起止时间只用来还原时段分布，最长允许跨 24 小时（跨天熬夜学习也算）
        check(ChronoUnit.MINUTES.between(startedAt, endedAt) <= 1440, "起止时间跨度不能超过 24 小时");

        String subject = tools.optionalText(input, "subject", 40);
        String task = tools.optionalText(input, "task", 120);
        if (taskId != null) {
            Map<String, Object> row = tools.requiredRow("SELECT title FROM study_tasks WHERE id=?", "计划任务不存在", taskId);
            if (task == null) task = String.valueOf(row.get("title"));
        }
        String note = tools.optionalText(input, "note", 255);
        String source = input.get("source") == null ? "timer" : String.valueOf(input.get("source"));
        check(Set.of("timer", "manual", "room", "plan").contains(source), "专注来源无效");

        db.update("INSERT INTO focus_sessions(user_id,subject,task,note,duration_minutes,started_at,ended_at,room_id,plan_id,task_id,source) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                user, subject == null ? "" : subject, task == null ? "" : task, note == null ? "" : note, duration, startedAt, endedAt, roomId, planId, taskId, source);
        long sessionId = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        if (roomId != null) {
            db.update("UPDATE study_room_members SET total_minutes=total_minutes+?,last_active_at=NOW() WHERE room_id=? AND user_id=?", duration, roomId, user);
            tools.refreshRoomTotals(roomId);
        }

        Map<String, Object> settings = tools.settings();
        int points = tools.setting(settings, "focus_points", 1);
        int cap = tools.setting(settings, "focus_daily_cap", 10);
        List<Map<String, Object>> badges = tools.award(user, "focus", points, "focus", sessionId, cap);

        Map<String, Object> result = new LinkedHashMap<>(tools.requiredRow("SELECT * FROM focus_sessions WHERE id=?", "专注记录不存在", sessionId));
        result.put("pointsAwarded", points);
        result.put("badges", badges);
        return result;
    }

    private LocalDateTime parseDateTime(Object value) {
        try {
            String text = String.valueOf(value).trim().replace(' ', 'T');
            return text.length() <= 16 ? LocalDateTime.parse(text) : LocalDateTime.parse(text.substring(0, 19));
        } catch (RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "时间格式无效");
        }
    }

    /** 专注历史，可限定时间段，供"专注记录"列表页使用。 */
    @GetMapping("/sessions")
    Map<String, Object> sessions(Authentication auth,
                                @RequestParam(required = false) String range,
                                @RequestParam(defaultValue = "1") int page) {
        long user = id(auth);
        LocalDate start = rangeStart(range);
        int offset = Math.max(0, Math.min(9999, page - 1)) * 20;
        List<Map<String, Object>> rows = db.queryForList("SELECT f.*,r.name AS room_name,p.title AS plan_title FROM focus_sessions f LEFT JOIN study_rooms r ON r.id=f.room_id LEFT JOIN study_plans p ON p.id=f.plan_id WHERE f.user_id=? AND f.started_at>=? ORDER BY f.started_at DESC LIMIT 21 OFFSET ?", user, start.atStartOfDay(), offset);
        boolean hasNext = rows.size() > 20;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", hasNext ? rows.subList(0, 20) : rows);
        result.put("hasNext", hasNext);
        result.put("page", Math.max(1, page));
        result.put("summary", summary(user, start, LocalDate.now()));
        return result;
    }

    // ---------- 统计 ----------

    @GetMapping("/stats")
    Map<String, Object> stats(Authentication auth, @RequestParam(defaultValue = "week") String range) {
        long user = id(auth);
        LocalDate today = LocalDate.now();
        LocalDate start = rangeStart(range);
        Map<String, Object> result = new LinkedHashMap<>(summary(user, start, today));
        result.put("range", range);
        result.put("start", start.toString());
        result.put("end", today.toString());

        List<Map<String, Object>> daily = db.queryForList("SELECT DATE(started_at) AS date,COUNT(*) AS sessions,SUM(duration_minutes) AS minutes FROM focus_sessions WHERE user_id=? AND started_at>=? GROUP BY DATE(started_at) ORDER BY date", user, start.atStartOfDay());
        List<Map<String, Object>> days = new ArrayList<>();
        for (int index = 0; index <= ChronoUnit.DAYS.between(start, today); index++) {
            LocalDate date = start.plusDays(index);
            Map<String, Object> found = daily.stream().filter(row -> date.equals(toDate(row.get("date")))).findFirst().orElse(null);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", date.toString());
            item.put("weekday", date.getDayOfWeek().getValue());
            item.put("minutes", found == null ? 0 : tools.rowNumber(found, "minutes"));
            item.put("sessions", found == null ? 0 : tools.rowNumber(found, "sessions"));
            days.add(item);
        }
        result.put("days", days);

        // 时段分布：24 格热力图，按周几 + 小时聚合最近 8 周的数据
        List<Map<String, Object>> hours = db.queryForList("SELECT DAYOFWEEK(started_at) AS dow,HOUR(started_at) AS hour,SUM(duration_minutes) AS minutes FROM focus_sessions WHERE user_id=? AND started_at>=DATE_SUB(CURRENT_DATE(),INTERVAL 56 DAY) GROUP BY DAYOFWEEK(started_at),HOUR(started_at)", user);
        List<Map<String, Object>> heatmap = new ArrayList<>();
        for (Map<String, Object> row : hours) {
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("weekday", (tools.rowNumber(row, "dow") + 5) % 7 + 1);
            cell.put("hour", tools.rowNumber(row, "hour"));
            cell.put("minutes", tools.rowNumber(row, "minutes"));
            heatmap.add(cell);
        }
        result.put("heatmap", heatmap);
        result.put("subjects", db.queryForList("SELECT subject,COUNT(*) AS sessions,SUM(duration_minutes) AS minutes FROM focus_sessions WHERE user_id=? AND started_at>=? AND subject<>'' GROUP BY subject ORDER BY minutes DESC LIMIT 10", user, start.atStartOfDay()));
        result.put("bestHour", heatmap.stream().max((left, right) -> Long.compare(tools.rowNumber(left, "minutes"), tools.rowNumber(right, "minutes"))).map(cell -> cell.get("hour")).orElse(null));
        return result;
    }

    private Map<String, Object> summary(long user, LocalDate start, LocalDate end) {
        Map<String, Object> row = tools.row("SELECT COUNT(*) AS sessions,COALESCE(SUM(duration_minutes),0) AS minutes,COALESCE(MAX(duration_minutes),0) AS longest FROM focus_sessions WHERE user_id=? AND started_at>=? AND started_at<?", user, start.atStartOfDay(), end.plusDays(1).atStartOfDay());
        Map<String, Object> result = new LinkedHashMap<>();
        long minutes = row == null ? 0 : tools.rowNumber(row, "minutes");
        result.put("sessions", row == null ? 0 : tools.rowNumber(row, "sessions"));
        result.put("minutes", minutes);
        result.put("hours", Math.round(minutes / 6.0) / 10.0);
        result.put("longest", row == null ? 0 : tools.rowNumber(row, "longest"));
        result.put("average", row == null || tools.rowNumber(row, "sessions") == 0 ? 0 : minutes / tools.rowNumber(row, "sessions"));
        result.put("today", tools.rowNumber(tools.row("SELECT COALESCE(SUM(duration_minutes),0) AS minutes FROM focus_sessions WHERE user_id=? AND DATE(started_at)=CURRENT_DATE()", user), "minutes"));
        result.put("week", tools.rowNumber(tools.row("SELECT COALESCE(SUM(duration_minutes),0) AS minutes FROM focus_sessions WHERE user_id=? AND YEARWEEK(started_at,1)=YEARWEEK(CURRENT_DATE(),1)", user), "minutes"));
        result.put("month", tools.rowNumber(tools.row("SELECT COALESCE(SUM(duration_minutes),0) AS minutes FROM focus_sessions WHERE user_id=? AND DATE_FORMAT(started_at,'%Y-%m')=DATE_FORMAT(CURRENT_DATE(),'%Y-%m')", user), "minutes"));
        result.put("total", tools.rowNumber(tools.row("SELECT COALESCE(SUM(duration_minutes),0) AS minutes FROM focus_sessions WHERE user_id=?", user), "minutes"));
        return result;
    }

    private LocalDate rangeStart(String range) {
        LocalDate today = LocalDate.now();
        return switch (range == null ? "week" : range) {
            case "day" -> today;
            case "month" -> today.minusDays(29);
            case "year" -> today.minusDays(364);
            case "all" -> today.minusDays(3650);
            default -> today.minusDays(6);
        };
    }

    // ---------- 排行 ----------

    /** 专注排行（周/月/总榜），只统计公开可见的数据，榜单本身不需要登录。 */
    @GetMapping("/leaderboard")
    Map<String, Object> leaderboard(@RequestParam(defaultValue = "week") String range,
                                    @RequestParam(defaultValue = "1") int page,
                                    Authentication auth) {
        LocalDate start = switch (range == null ? "week" : range) {
            case "month" -> LocalDate.now().minusDays(29);
            case "all" -> LocalDate.of(2000, 1, 1);
            default -> LocalDate.now().minusDays(6);
        };
        int size = 20;
        int offset = Math.max(0, Math.min(999, page - 1)) * size;
        String sql = "SELECT f.user_id,u.username,u.points,"
                + "SUM(CASE WHEN f.started_at>=? THEN f.duration_minutes ELSE 0 END) AS range_minutes,"
                + "COUNT(CASE WHEN f.started_at>=? THEN 1 END) AS range_sessions,"
                + "SUM(f.duration_minutes) AS total_minutes "
                + "FROM focus_sessions f JOIN users u ON u.id=f.user_id WHERE u.status='active' "
                + "GROUP BY f.user_id,u.username,u.points ORDER BY range_minutes DESC,total_minutes DESC LIMIT ? OFFSET ?";
        List<Map<String, Object>> items = db.queryForList(sql, start.atStartOfDay(), start.atStartOfDay(), size + 1, offset);
        boolean hasNext = items.size() > size;
        List<Map<String, Object>> rows = new ArrayList<>(hasNext ? items.subList(0, size) : items);
        long me = auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal()) ? id(auth) : 0;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("range", range);
        result.put("items", rows);
        result.put("hasNext", hasNext);
        result.put("page", Math.max(1, page));
        result.put("mine", me == 0 ? null : tools.row("SELECT SUM(duration_minutes) AS minutes,COUNT(*) AS sessions FROM focus_sessions WHERE user_id=? AND started_at>=?", me, start.atStartOfDay()));
        return result;
    }

    /** 我的自习室记录：累计专注、参与过的自习室、今日/本周时长。 */
    @GetMapping("/me/rooms")
    Map<String, Object> myRooms(Authentication auth) {
        long user = id(auth);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("participated", db.queryForList("SELECT r.id,r.name,r.status,r.owner_id,r.focus_minutes,r.created_at,m.total_minutes,m.joined_at FROM study_room_members m JOIN study_rooms r ON r.id=m.room_id WHERE m.user_id=? ORDER BY r.created_at DESC LIMIT 20", user));
        result.put("owned", db.queryForList("SELECT id,name,status,member_count,focus_minutes,created_at FROM study_rooms WHERE owner_id=? ORDER BY created_at DESC LIMIT 20", user));
        return result;
    }
}
