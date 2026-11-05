package com.studentforum.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import com.studentforum.repository.FocusRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 番茄钟：记录专注、专注统计、专注排行。
 * 专注记录是"自习室排行"和"与计划/打卡联动"的唯一数据源，所以时长全部落在这张表里。
 */
@Service
public class FocusService {
    /** 一次专注最长 12 小时：再长也不像是真实计时，多半是前端没停表。 */
    private static final int MAX_MINUTES = 720;

    private final FocusRepository focus;
    private final AccountAccessService accounts;
    private final StudyToolService tools;
    private final StudyRoomAccessService roomAccess;
    private final StudySettingsService studySettings;
    private final StudyRewardService rewards;

    public FocusService(FocusRepository focus, AccountAccessService accounts, StudyToolService tools, StudyRoomAccessService roomAccess, StudySettingsService studySettings, StudyRewardService rewards) {
        this.focus = focus;
        this.accounts = accounts;
        this.tools = tools;
        this.roomAccess = roomAccess;
        this.studySettings = studySettings;
        this.rewards = rewards;
    }

    private Map<String, Object> required(Map<String, Object> row, String message) {
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
        return row;
    }

    private long id(Authentication auth) {
        return accounts.id(auth);
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
    @Transactional
    public Map<String, Object> createSession(Authentication auth, Map<String, Object> input) {
        accounts.writable(auth);
        long user = id(auth);
        int duration = (int) tools.number(input, "durationMinutes", 0);
        check(duration >= 1 && duration <= MAX_MINUTES, "专注时长需要在 1 到 720 分钟之间");

        Long roomId = input.get("roomId") == null ? null : tools.number(input, "roomId", 0);
        if (roomId != null && roomId > 0) {
            Map<String, Object> room = required(focus.room(roomId), "自习室不存在");
            check("active".equals(String.valueOf(room.get("status"))), "自习室已关闭");
            roomAccess.requireRoomView(room, user);
            // 在自习室里开始专注就等于加入自习室，否则时长累加会落空、房间榜也看不到自己
            if (!focus.isMember(roomId, user)) {
                focus.join(roomId, user);
                roomAccess.refreshRoomTotals(roomId);
            }
        } else roomId = null;

        Long planId = input.get("planId") == null ? null : tools.number(input, "planId", 0);
        Long taskId = input.get("taskId") == null ? null : tools.number(input, "taskId", 0);
        if (planId != null && planId > 0) required(focus.plan(planId, user), "学习计划不存在");
        else planId = null;
        if (taskId != null && taskId > 0) required(focus.task(taskId, user), "计划任务不存在");
        else taskId = null;

        LocalDateTime endedAt = input.get("endedAt") == null ? LocalDateTime.now() : parseDateTime(input.get("endedAt"));
        LocalDateTime startedAt = input.get("startedAt") == null ? endedAt.minusMinutes(duration) : parseDateTime(input.get("startedAt"));
        check(!startedAt.isAfter(endedAt), "开始时间不能晚于结束时间");
        // 起止时间只用来还原时段分布，最长允许跨 24 小时（跨天熬夜学习也算）
        check(ChronoUnit.MINUTES.between(startedAt, endedAt) <= 1440, "起止时间跨度不能超过 24 小时");

        String subject = tools.optionalText(input, "subject", 40);
        String task = tools.optionalText(input, "task", 120);
        if (taskId != null) {
            Map<String, Object> row = required(focus.taskTitle(taskId), "计划任务不存在");
            if (task == null) task = String.valueOf(row.get("title"));
        }
        String note = tools.optionalText(input, "note", 255);
        String source = input.get("source") == null ? "timer" : String.valueOf(input.get("source"));
        check(Set.of("timer", "manual", "room", "plan").contains(source), "专注来源无效");

        long sessionId = focus.insertSession(user, subject == null ? "" : subject, task == null ? "" : task, note == null ? "" : note, duration, startedAt, endedAt, roomId, planId, taskId, source);

        if (roomId != null) {
            focus.addMinutes(duration, roomId, user);
            roomAccess.refreshRoomTotals(roomId);
        }

        Map<String, Object> settings = studySettings.settings();
        int points = studySettings.setting(settings, "focus_points", 1);
        int cap = studySettings.setting(settings, "focus_daily_cap", 10);
        List<Map<String, Object>> badges = rewards.award(user, "focus", points, "focus", sessionId, cap);

        Map<String, Object> result = new LinkedHashMap<>(required(focus.session(sessionId), "专注记录不存在"));
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
    public Map<String, Object> sessions(Authentication auth,
                                String range,
                                int page) {
        long user = id(auth);
        LocalDate start = rangeStart(range);
        int offset = Math.max(0, Math.min(9999, page - 1)) * 20;
        List<Map<String, Object>> rows = focus.sessions(user, start.atStartOfDay(), offset);
        boolean hasNext = rows.size() > 20;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", hasNext ? rows.subList(0, 20) : rows);
        result.put("hasNext", hasNext);
        result.put("page", Math.max(1, page));
        result.put("summary", summary(user, start, LocalDate.now()));
        return result;
    }

    // ---------- 统计 ----------

    public Map<String, Object> stats(Authentication auth, String range) {
        long user = id(auth);
        LocalDate today = LocalDate.now();
        LocalDate start = rangeStart(range);
        Map<String, Object> result = new LinkedHashMap<>(summary(user, start, today));
        result.put("range", range);
        result.put("start", start.toString());
        result.put("end", today.toString());

        List<Map<String, Object>> daily = focus.daily(user, start.atStartOfDay());
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
        List<Map<String, Object>> hours = focus.hourlyHeatmap(user);
        List<Map<String, Object>> heatmap = new ArrayList<>();
        for (Map<String, Object> row : hours) {
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("weekday", (tools.rowNumber(row, "dow") + 5) % 7 + 1);
            cell.put("hour", tools.rowNumber(row, "hour"));
            cell.put("minutes", tools.rowNumber(row, "minutes"));
            heatmap.add(cell);
        }
        result.put("heatmap", heatmap);
        result.put("subjects", focus.subjects(user, start.atStartOfDay()));
        result.put("bestHour", heatmap.stream().max((left, right) -> Long.compare(tools.rowNumber(left, "minutes"), tools.rowNumber(right, "minutes"))).map(cell -> cell.get("hour")).orElse(null));
        return result;
    }

    private Map<String, Object> summary(long user, LocalDate start, LocalDate end) {
        Map<String, Object> row = focus.summary(user, start.atStartOfDay(), end.plusDays(1).atStartOfDay());
        Map<String, Object> result = new LinkedHashMap<>();
        long minutes = row == null ? 0 : tools.rowNumber(row, "minutes");
        result.put("sessions", row == null ? 0 : tools.rowNumber(row, "sessions"));
        result.put("minutes", minutes);
        result.put("hours", Math.round(minutes / 6.0) / 10.0);
        result.put("longest", row == null ? 0 : tools.rowNumber(row, "longest"));
        result.put("average", row == null || tools.rowNumber(row, "sessions") == 0 ? 0 : minutes / tools.rowNumber(row, "sessions"));
        result.put("today", tools.rowNumber(focus.todayMinutes(user), "minutes"));
        result.put("week", tools.rowNumber(focus.weekMinutes(user), "minutes"));
        result.put("month", tools.rowNumber(focus.monthMinutes(user), "minutes"));
        result.put("total", tools.rowNumber(focus.totalMinutes(user), "minutes"));
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
    public Map<String, Object> leaderboard(String range,
                                    int page,
                                    Authentication auth) {
        LocalDate start = switch (range == null ? "week" : range) {
            case "month" -> LocalDate.now().minusDays(29);
            case "all" -> LocalDate.of(2000, 1, 1);
            default -> LocalDate.now().minusDays(6);
        };
        int size = 20;
        int offset = Math.max(0, Math.min(999, page - 1)) * size;
        List<Map<String, Object>> items = focus.leaderboard(start.atStartOfDay(), size + 1, offset);
        boolean hasNext = items.size() > size;
        List<Map<String, Object>> rows = new ArrayList<>(hasNext ? items.subList(0, size) : items);
        long me = auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal()) ? id(auth) : 0;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("range", range);
        result.put("items", rows);
        result.put("hasNext", hasNext);
        result.put("page", Math.max(1, page));
        result.put("mine", me == 0 ? null : focus.myLeaderboard(me, start.atStartOfDay()));
        return result;
    }

    /** 我的自习室记录：累计专注、参与过的自习室、今日/本周时长。 */
    public Map<String, Object> myRooms(Authentication auth) {
        long user = id(auth);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("participated", focus.participatedRooms(user));
        result.put("owned", focus.ownedRooms(user));
        return result;
    }
}
