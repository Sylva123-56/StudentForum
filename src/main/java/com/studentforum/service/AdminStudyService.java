package com.studentforum.service;

import com.studentforum.repository.StudySettingsRepository;
import com.studentforum.repository.AdminStudyRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 学习工具的后台：打卡积分与规则、专注数据概览、自习室治理。
 * 三个 section 合并在一个控制器里，是因为它们是同一套"学习工具运营"操作，共用顶部 tab。
 */
@Service
public class AdminStudyService {
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

    private final AdminStudyRepository repository;
    private final AccountAccessService accounts;
    private final StudyToolService tools;
    private final StudyRoomAccessService roomAccess;
    private final StudySettingsService studySettings;
    private final StudySettingsRepository settingsRepository;

    public AdminStudyService(AdminStudyRepository repository, AccountAccessService accounts, StudyToolService tools, StudyRoomAccessService roomAccess, StudySettingsService studySettings, StudySettingsRepository settingsRepository) {
        this.repository = repository;
        this.accounts = accounts;
        this.tools = tools;
        this.roomAccess = roomAccess;
        this.studySettings = studySettings;
        this.settingsRepository = settingsRepository;
    }

    private long id(Authentication auth) {
        return accounts.id(auth);
    }

    private Map<String, Object> required(Map<String, Object> row, String message) {
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
        return row;
    }

    private void check(boolean condition, String message) {
        if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private void log(long actor, String action, String targetType, long targetId, String detail) {
        repository.log(actor, action, targetType, targetId, detail);
    }

    // ---------- 打卡：规则与数据 ----------

    /** 打卡 section：规则配置 + 打卡总览 + 最近打卡明细，一次请求渲染整个后台页。 */
    public Map<String, Object> checkin(Authentication auth) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> settings = studySettings.settings();
        settings.put("allow_makeup", studySettings.allowMakeup());
        result.put("settings", settings);
        result.put("today", repository.todayCheckins());
        result.put("total", repository.totalCheckins());
        result.put("makeup", repository.recentMakeups());
        result.put("goals", repository.goalSummary());
        result.put("trend", repository.checkinTrend());
        result.put("subjects", repository.checkinSubjects());
        result.put("badges", repository.badges());
        result.put("recent", repository.recentCheckins());
        return result;
    }

    /** 保存打卡规则：只接受白名单里的键，越界拒绝，避免管理员把积分系统配成负数。 */
    @Transactional
    public Map<String, Object> saveSettings(Authentication auth, Map<String, Object> input) {
        long actor = id(auth);
        List<String> changed = new ArrayList<>();
        Map<String, Object> changes = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> entry : NUMBERS.entrySet()) {
            if (!input.containsKey(entry.getKey())) continue;
            long value = tools.number(input, entry.getKey(), entry.getValue()[0]);
            check(value >= entry.getValue()[0] && value <= entry.getValue()[1], entry.getKey() + " 超出允许范围（" + entry.getValue()[0] + "-" + entry.getValue()[1] + "）");
            changes.put(entry.getKey(), value);
            changed.add(entry.getKey());
        }
        if (input.containsKey("allow_makeup")) {
            boolean allow = Boolean.TRUE.equals(input.get("allow_makeup")) || "true".equals(String.valueOf(input.get("allow_makeup")));
            changes.put("allow_makeup", allow);
            changed.add("allow_makeup");
        }
        check(!changes.isEmpty(), "没有需要保存的规则");
        settingsRepository.update(changes);
        log(actor, "update_checkin_settings", "checkin_settings", 1, "修改打卡规则：" + String.join(",", changed));
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> settings = studySettings.settings();
        settings.put("allow_makeup", studySettings.allowMakeup());
        result.put("settings", settings);
        result.put("changed", changed);
        return result;
    }

    // ---------- 专注：数据概览 ----------

    public Map<String, Object> focus(Authentication auth, String range) {
        LocalDate start = switch (range == null ? "week" : range) {
            case "month" -> LocalDate.now().minusDays(29);
            case "year" -> LocalDate.now().minusDays(364);
            default -> LocalDate.now().minusDays(6);
        };
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("range", range);
        result.put("summary", repository.focusSummary(start.atStartOfDay()));
        result.put("today", repository.todayFocus());
        result.put("trend", repository.focusTrend(start.atStartOfDay()));
        result.put("subjects", repository.focusSubjects(start.atStartOfDay()));
        result.put("sources", repository.focusSources(start.atStartOfDay()));
        result.put("top", repository.focusLeaders(start.atStartOfDay()));
        // 异常时长：单次超过 4 小时或不足 1 分钟的记录，多半是前端没停表或误报，后台要能看见
        result.put("suspicious", repository.abnormalSessions());
        return result;
    }

    /** 后台清理明显的异常专注记录（误计时），并写审计日志。 */
    @Transactional
    public Map<String, Object> deleteSession(Authentication auth, long sessionId) {
        long actor = id(auth);
        Map<String, Object> session = required(repository.findSession(sessionId), "专注记录不存在");
        repository.deleteSession(sessionId);
        Long roomId = session.get("room_id") == null ? null : tools.rowNumber(session, "room_id");
        if (roomId != null) roomAccess.refreshRoomTotals(roomId);
        log(actor, "delete_focus_session", "focus_session", sessionId, "删除异常专注记录，时长 " + tools.rowNumber(session, "duration_minutes") + " 分钟");
        return Map.of("deleted", true);
    }

    // ---------- 自习室：治理 ----------

    public Map<String, Object> rooms(Authentication auth,
                              String status,
                              String keyword) {
        if (status != null && !status.isBlank() && !"all".equals(status)) check("active".equals(status) || "closed".equals(status), "自习室状态无效");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", repository.rooms(status));
        result.put("summary", repository.roomSummary());
        result.put("active", repository.activeMembers());
        result.put("leaders", repository.roomLeaders());
        return result;
    }

    /** 关闭/恢复一个自习室：违规房间直接关掉，但保留成员数据以便复核。 */
    @Transactional
    public Map<String, Object> updateRoom(Authentication auth, long roomId, Map<String, Object> input) {
        long actor = id(auth);
        Map<String, Object> room = required(repository.findRoom(roomId), "自习室不存在");
        String status = input.containsKey("status") ? String.valueOf(input.get("status")) : String.valueOf(room.get("status"));
        check("active".equals(status) || "closed".equals(status), "自习室状态无效");
        boolean muted = input.containsKey("muted") ? Boolean.TRUE.equals(input.get("muted")) || "true".equals(String.valueOf(input.get("muted"))) : tools.rowFlag(room, "muted");
        repository.updateRoom(status, muted, roomId);
        if ("closed".equals(status) && !"closed".equals(room.get("status"))) {
            repository.recordAdminClose(roomId, actor);
        }
        log(actor, "update_study_room", "study_room", roomId, "状态 " + room.get("status") + " → " + status + "，静音=" + muted);
        return roomAccess.room(roomId);
    }

    /** 删除自习室：级联删掉成员与消息，属于不可逆操作，交给超管。 */
    @Transactional
    public Map<String, Object> deleteRoom(Authentication auth, long roomId) {
        long actor = id(auth);
        Map<String, Object> room = required(repository.findRoom(roomId), "自习室不存在");
        repository.deleteRoom(roomId);
        log(actor, "delete_study_room", "study_room", roomId, "删除自习室「" + room.get("name") + "」（房主 " + room.get("owner_id") + "）");
        return Map.of("deleted", true);
    }
}
