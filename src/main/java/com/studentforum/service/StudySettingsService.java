package com.studentforum.service;

import com.studentforum.repository.StudySettingsRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class StudySettingsService {
    private final StudySettingsRepository repository;

    public StudySettingsService(StudySettingsRepository repository) {
        this.repository = repository;
    }

    public Map<String, Object> settings() {
        List<Map<String, Object>> rows = repository.find();
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

    public int setting(Map<String, Object> settings, String key, int fallback) {
        Object value = settings.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    public boolean allowMakeup() {
        Object value = settings().get("allow_makeup");
        if (value instanceof Boolean flag) return flag;
        return value instanceof Number number ? number.intValue() != 0 : true;
    }

}
