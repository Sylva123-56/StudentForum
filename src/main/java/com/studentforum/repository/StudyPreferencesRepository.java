package com.studentforum.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StudyPreferencesRepository {
    private final JdbcTemplate db;

    public StudyPreferencesRepository(JdbcTemplate db) {
        this.db = db;
    }

    public List<Map<String, Object>> myGroups(long userId) {
        return db.queryForList("SELECT g.id,g.name,g.subject FROM study_group_members m JOIN study_groups g ON g.id=m.group_id WHERE m.user_id=? AND g.status='active' ORDER BY g.name", userId);
    }

    public Map<String, Object> subjectPreference(long userId) {
        return db.queryForList("SELECT subject_preference FROM users WHERE id=?", userId).stream().findFirst().orElse(null);
    }
}
