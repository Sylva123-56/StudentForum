package com.studentforum.repository;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StudySettingsRepository {
    private static final Set<String> COLUMNS = Set.of("checkin_points", "streak_bonus", "streak_bonus_days",
            "focus_points", "focus_daily_cap", "plan_task_points", "plan_task_daily_cap",
            "plan_complete_points", "allow_makeup");
    private final JdbcTemplate db;

    public StudySettingsRepository(JdbcTemplate db) {
        this.db = db;
    }

    public List<Map<String, Object>> find() {
        return db.queryForList("SELECT * FROM checkin_settings WHERE id=1");
    }

    public void update(Map<String, Object> validatedSettings) {
        if (validatedSettings.isEmpty() || !COLUMNS.containsAll(validatedSettings.keySet())) {
            throw new IllegalArgumentException("Unsupported study setting");
        }
        var assignments = new ArrayList<String>();
        var values = new ArrayList<>(validatedSettings.values());
        for (String column : validatedSettings.keySet()) assignments.add(column + "=?");
        values.add(1L);
        db.update("UPDATE checkin_settings SET " + String.join(",", assignments) + " WHERE id=?", values.toArray());
    }
}
