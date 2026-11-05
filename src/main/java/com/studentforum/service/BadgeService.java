package com.studentforum.service;

import com.studentforum.repository.BadgeRepository;
import com.studentforum.model.StudyStreak;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BadgeService {
    private static final List<String> BADGES = List.of(
            "checkin_first", "checkin_streak_3", "checkin_streak_7", "checkin_streak_30",
            "focus_first", "focus_10h", "focus_50h",
            "plan_first", "plan_5", "plan_complete", "room_host");

    private final BadgeRepository badges;
    private final NotificationService notifications;

    public BadgeService(BadgeRepository badges, NotificationService notifications) {
        this.badges = badges;
        this.notifications = notifications;
    }

    @Transactional
    public List<Map<String, Object>> evaluateBadges(long userId) {
        int bestStreak = 0;
        LocalDate today = LocalDate.now();
        for (Map<String, Object> goal : badges.goals(userId)) {
            bestStreak = Math.max(bestStreak, new StudyStreak(badges.checkinDates(((Number) goal.get("id")).longValue(), userId)
                    .stream().map(row -> StudyToolService.toDate(row.get("checkin_date")))
                    .filter(java.util.Objects::nonNull).toList(), today).length());
        }
        long checkinCount = badges.checkins(userId);
        long focusMinutes = badges.focusMinutes(userId);
        long focusSessions = badges.focusSessions(userId);
        long plans = badges.plans(userId);
        long completedPlans = badges.completedPlans(userId);
        long hostedRooms = badges.hostedRooms(userId);

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
        if (!badges.insert(userId, code, name, detail)) return;
        Map<String, Object> badge = new LinkedHashMap<>();
        badge.put("code", code);
        badge.put("name", name);
        badge.put("detail", detail);
        earned.add(badge);
        notifications.notifyUser(userId, "badge", "获得新徽章：" + name, null);
    }

    public List<Map<String, Object>> badgeCatalog(long userId) {
        List<Map<String, Object>> owned = badges.owned(userId);
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
}
