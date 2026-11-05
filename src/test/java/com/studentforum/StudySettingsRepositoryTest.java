package com.studentforum;

import com.studentforum.repository.StudySettingsRepository;
import com.studentforum.service.StudySettingsService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudySettingsRepositoryTest {
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final StudySettingsRepository repository = new StudySettingsRepository(db);

    @Test void missingConfigurationKeepsTheEstablishedDefaults() {
        when(db.queryForList("SELECT * FROM checkin_settings WHERE id=1")).thenReturn(List.of());
        Map<String, Object> result = new StudySettingsService(repository).settings();
        assertEquals(2, result.get("checkin_points"));
        assertEquals(10, result.get("focus_daily_cap"));
        assertEquals(true, result.get("allow_makeup"));
    }

    @Test void updateOnlyUsesKnownColumnsAndPreservesParameterOrder() {
        var values = new LinkedHashMap<String, Object>();
        values.put("checkin_points", 3L);
        values.put("allow_makeup", false);
        repository.update(values);
        verify(db).update("UPDATE checkin_settings SET checkin_points=?,allow_makeup=? WHERE id=?",
                3L, false, 1L);
    }

    @Test void invalidColumnCannotBeInjectedIntoUpdate() {
        assertThrows(IllegalArgumentException.class,
                () -> repository.update(Map.of("allow_makeup=TRUE WHERE id=1; --", false)));
        verifyNoInteractions(db);
    }
}
