package com.studentforum;

import com.studentforum.repository.StudyPreferencesRepository;
import com.studentforum.service.StudyPreferencesService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudyPreferencesServiceTest {
    @Test void subjectsKeepOriginalPreferenceOrderAndDeduplicateGroupSubjects() {
        StudyPreferencesRepository repository = mock(StudyPreferencesRepository.class);
        when(repository.subjectPreference(7)).thenReturn(Map.of("subject_preference", "数学，英语/数学"));
        when(repository.myGroups(7)).thenReturn(List.of(Map.of("subject", "物理"), Map.of("subject", "英语")));
        assertEquals(List.of("数学", "英语", "物理"), new StudyPreferencesService(repository).subjects(7));
    }
}
