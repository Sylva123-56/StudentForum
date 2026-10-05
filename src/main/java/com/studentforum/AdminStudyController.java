package com.studentforum;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 学习工具的后台：打卡积分与规则、专注数据概览、自习室治理。
 * 三个 section 合并在一个控制器里，是因为它们是同一套"学习工具运营"操作，共用顶部 tab。
 */
@RestController
@RequestMapping("/api/admin/study-tools")
class AdminStudyController {
    /** 可配置的积分/规则项：键 → [最小值, 最大值]，越界直接 400 而不是静默纠正。 */
    private static final Map<String, int[]> NUMBERS = Map.of(
            "checkin_points", new int[]{0, 100},
            "streak_bonus", new int[]{0, 100},
            "streak_bonus_days", new int[]{1, 365},
            "focus_points", new int[]{0, 100},
            "focus_daily_cap", new int[]{0, 1440},
            "plan_task_points", new int[]{0, 100},
            "plan_task_daily_cap", new int[]{0, 1440},
            "plan_complete_points", new int[]{0, 500});

    private final JdbcTemplate db;
    private final ForumService service;
    private final StudyToolService tools;

    AdminStudyController(JdbcTemplate db, ForumService service, StudyToolService tools) {
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

    private void log(long actor, String action, String targetType, long targetId, String detail) {
        db.update("INSERT INTO admin_logs(actor_id,action,target_type,target_id,detail) VALUES(?,?,?,?,?)", actor, action, targetType, targetId, detail);
    }

    // ---------- 打卡：规则与数据 ----------

    /** 打卡 section：规则配置 + 打卡总览 + 最近打卡明细，一次请求渲染整个后台页。 */
    @GetMapping("/checkin")
    Map<String, Object> checkin(Authentication auth) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> settings = tools.settings();
        settings.put("allow_makeup", tools.allowMakeup());
        result.put("settings", settings);
        result.put("today", tools.row("SELECT COUNT(*) AS checkins,COUNT(DISTINCT user_id) AS users,COALESCE(SUM(duration_minutes),0) AS minutes,COALESCE(SUM(points),0) AS points FROM checkins WHERE checkin_date=CURRENT_DATE()"));
        result.put("total", tools.row("SELECT COUNT(*) AS checkins,COUNT(DISTINCT user_id) AS users,COALESCE(SUM(duration_minutes),0) AS minutes,COALESCE(SUM(points),0) AS points FROM checkins"));
        result.put("makeup", tools.row("SELECT COUNT(*) AS count FROM checkins WHERE make_up=TRUE AND checkin_date>=DATE_SUB(CURRENT_DATE(),INTERVAL 30 DAY)"));
        result.put("goals", tools.row("SELECT COUNT(*) AS total,COALESCE(SUM(status='active'),0) AS active,COALESCE(SUM(visibility='group'),0) AS group_goals FROM checkin_goals"));
        result.put("trend", db.queryForList("SELECT checkin_date AS date,COUNT(*) AS count,COUNT(DISTINCT user_id) AS users FROM checkins WHERE checkin_date>=DATE_SUB(CURRENT_DATE(),INTERVAL 13 DAY) GROUP BY checkin_date ORDER BY checkin_date"));
        result.put("subjects", db.queryForList("SELECT subject,COUNT(*) AS count,SUM(duration_minutes) AS minutes FROM checkins WHERE subject<>'' AND checkin_date>=DATE_SUB(CURRENT_DATE(),INTERVAL 30 DAY) GROUP BY subject ORDER BY count DESC LIMIT 10"));
        result.put("badges", db.queryForList("SELECT code,name,COUNT(*) AS count FROM user_badges GROUP BY code,name ORDER BY count DESC LIMIT 20"));
        result.put("recent", db.queryForList("SELECT c.id,c.user_id,u.username,c.goal_id,g.title AS goal_title,c.content,c.subject,c.duration_minutes,c.make_up,c.checkin_date,c.points,c.created_at FROM checkins c JOIN users u ON u.id=c.user_id LEFT JOIN checkin_goals g ON g.id=c.goal_id ORDER BY c.created_at DESC LIMIT 30"));
        return result;
    }

    /** 保存打卡规则：只接受白名单里的键，越界拒绝，避免管理员把积分系统配成负数。 */
    @PatchMapping("/checkin/settings")
    @Transactional
    Map<String, Object> saveSettings(Authentication auth, @RequestBody Map<String, Object> input) {
        long actor = id(auth);
        List<String> changed = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        List<String> columns = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : NUMBERS.entrySet()) {
            if (!input.containsKey(entry.getKey())) continue;
            long value = tools.number(input, entry.getKey(), entry.getValue()[0]);
            check(value >= entry.getValue()[0] && value <= entry.getValue()[1], entry.getKey() + " 超出允许范围（" + entry.getValue()[0] + "-" + entry.getValue()[1] + "）");
            columns.add(entry.getKey() + "=?");
            values.add(value);
            changed.add(entry.getKey());
        }
        if (input.containsKey("allow_makeup")) {
            boolean allow = Boolean.TRUE.equals(input.get("allow_makeup")) || "true".equals(String.valueOf(input.get("allow_makeup")));
            columns.add("allow_makeup=?");
            values.add(allow);
            changed.add("allow_makeup");
        }
        check(!columns.isEmpty(), "没有需要保存的规则");
        values.add(1L);
        db.update("UPDATE checkin_settings SET " + String.join(",", columns) + " WHERE id=?", values.toArray());
        log(actor, "update_checkin_settings", "checkin_settings", 1, "修改打卡规则：" + String.join(",", changed));
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> settings = tools.settings();
        settings.put("allow_makeup", tools.allowMakeup());
        result.put("settings", settings);
        result.put("changed", changed);
        return result;
    }

    // ---------- 专注：数据概览 ----------

    @GetMapping("/focus")
    Map<String, Object> focus(Authentication auth, @RequestParam(defaultValue = "week") String range) {
        LocalDate start = switch (range == null ? "week" : range) {
            case "month" -> LocalDate.now().minusDays(29);
            case "year" -> LocalDate.now().minusDays(364);
            default -> LocalDate.now().minusDays(6);
        };
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("range", range);
        result.put("summary", tools.row("SELECT COUNT(*) AS sessions,COUNT(DISTINCT user_id) AS users,COALESCE(SUM(duration_minutes),0) AS minutes,COALESCE(AVG(duration_minutes),0) AS average FROM focus_sessions WHERE started_at>=?", start.atStartOfDay()));
        result.put("today", tools.row("SELECT COUNT(*) AS sessions,COUNT(DISTINCT user_id) AS users,COALESCE(SUM(duration_minutes),0) AS minutes FROM focus_sessions WHERE DATE(started_at)=CURRENT_DATE()"));
        result.put("trend", db.queryForList("SELECT DATE(started_at) AS date,COUNT(*) AS sessions,COUNT(DISTINCT user_id) AS users,SUM(duration_minutes) AS minutes FROM focus_sessions WHERE started_at>=? GROUP BY DATE(started_at) ORDER BY date", start.atStartOfDay()));
        result.put("subjects", db.queryForList("SELECT subject,COUNT(*) AS sessions,SUM(duration_minutes) AS minutes FROM focus_sessions WHERE subject<>'' AND started_at>=? GROUP BY subject ORDER BY minutes DESC LIMIT 10", start.atStartOfDay()));
        result.put("sources", db.queryForList("SELECT source,COUNT(*) AS sessions,SUM(duration_minutes) AS minutes FROM focus_sessions WHERE started_at>=? GROUP BY source ORDER BY sessions DESC", start.atStartOfDay()));
        result.put("top", db.queryForList("SELECT f.user_id,u.username,COUNT(*) AS sessions,SUM(f.duration_minutes) AS minutes FROM focus_sessions f JOIN users u ON u.id=f.user_id WHERE f.started_at>=? GROUP BY f.user_id,u.username ORDER BY minutes DESC LIMIT 20", start.atStartOfDay()));
        // 异常时长：单次超过 4 小时或不足 1 分钟的记录，多半是前端没停表或误报，后台要能看见
        result.put("suspicious", db.queryForList("SELECT f.id,f.user_id,u.username,f.duration_minutes,f.subject,f.source,f.started_at,f.ended_at FROM focus_sessions f JOIN users u ON u.id=f.user_id WHERE f.duration_minutes>240 OR f.duration_minutes<1 ORDER BY f.started_at DESC LIMIT 30"));
        return result;
    }

    /** 后台清理明显的异常专注记录（误计时），并写审计日志。 */
    @DeleteMapping("/focus/{sessionId}")
    @Transactional
    Map<String, Object> deleteSession(Authentication auth, @PathVariable long sessionId) {
        long actor = id(auth);
        Map<String, Object> session = tools.requiredRow("SELECT * FROM focus_sessions WHERE id=?", "专注记录不存在", sessionId);
        db.update("DELETE FROM focus_sessions WHERE id=?", sessionId);
        Long roomId = session.get("room_id") == null ? null : tools.rowNumber(session, "room_id");
        if (roomId != null) tools.refreshRoomTotals(roomId);
        log(actor, "delete_focus_session", "focus_session", sessionId, "删除异常专注记录，时长 " + tools.rowNumber(session, "duration_minutes") + " 分钟");
        return Map.of("deleted", true);
    }

    // ---------- 自习室：治理 ----------

    @GetMapping("/study-rooms")
    Map<String, Object> rooms(Authentication auth,
                              @RequestParam(required = false) String status,
                              @RequestParam(required = false) String keyword) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT r.*,u.username AS owner_username,(SELECT COUNT(*) FROM study_room_members m WHERE m.room_id=r.id) AS members,(SELECT COUNT(*) FROM study_room_messages x WHERE x.room_id=r.id) AS messages FROM study_rooms r JOIN users u ON u.id=r.owner_id WHERE 1=1");
        if (status != null && !status.isBlank() && !"all".equals(status)) {
            check("active".equals(status) || "closed".equals(status), "自习室状态无效");
            sql.append(" AND r.status=?");
            args.add(status);
        }
        sql.append(" ORDER BY r.created_at DESC LIMIT 100");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", db.queryForList(sql.toString(), args.toArray()));
        result.put("summary", tools.row("SELECT COUNT(*) AS total,COALESCE(SUM(status='active'),0) AS active,COALESCE(SUM(status='closed'),0) AS closed,COALESCE(SUM(visibility='private'),0) AS private_rooms,COALESCE(SUM(focus_minutes),0) AS focus_minutes FROM study_rooms"));
        result.put("active", tools.row("SELECT COUNT(*) AS members FROM study_room_members WHERE last_active_at>=DATE_SUB(NOW(),INTERVAL 1 DAY)"));
        result.put("leaders", db.queryForList("SELECT r.id,r.name,r.focus_minutes,u.username AS owner_username FROM study_rooms r JOIN users u ON u.id=r.owner_id ORDER BY r.focus_minutes DESC LIMIT 10"));
        return result;
    }

    /** 关闭/恢复一个自习室：违规房间直接关掉，但保留成员数据以便复核。 */
    @PatchMapping("/study-rooms/{roomId}")
    @Transactional
    Map<String, Object> updateRoom(Authentication auth, @PathVariable long roomId, @RequestBody Map<String, Object> input) {
        long actor = id(auth);
        Map<String, Object> room = tools.requiredRow("SELECT * FROM study_rooms WHERE id=?", "自习室不存在", roomId);
        String status = input.containsKey("status") ? String.valueOf(input.get("status")) : String.valueOf(room.get("status"));
        check("active".equals(status) || "closed".equals(status), "自习室状态无效");
        boolean muted = input.containsKey("muted") ? Boolean.TRUE.equals(input.get("muted")) || "true".equals(String.valueOf(input.get("muted"))) : tools.rowFlag(room, "muted");
        db.update("UPDATE study_rooms SET status=?,muted=? WHERE id=?", status, muted, roomId);
        if ("closed".equals(status) && !"closed".equals(room.get("status"))) {
            db.update("INSERT INTO study_room_messages(room_id,user_id,kind,content) VALUES(?,?,'system','管理员关闭了该自习室')", roomId, actor);
        }
        log(actor, "update_study_room", "study_room", roomId, "状态 " + room.get("status") + " → " + status + "，静音=" + muted);
        return tools.room(roomId);
    }

    /** 删除自习室：级联删掉成员与消息，属于不可逆操作，交给超管。 */
    @DeleteMapping("/study-rooms/{roomId}")
    @Transactional
    Map<String, Object> deleteRoom(Authentication auth, @PathVariable long roomId) {
        long actor = id(auth);
        Map<String, Object> room = tools.requiredRow("SELECT * FROM study_rooms WHERE id=?", "自习室不存在", roomId);
        db.update("DELETE FROM study_rooms WHERE id=?", roomId);
        log(actor, "delete_study_room", "study_room", roomId, "删除自习室「" + room.get("name") + "」（房主 " + room.get("owner_id") + "）");
        return Map.of("deleted", true);
    }
}
