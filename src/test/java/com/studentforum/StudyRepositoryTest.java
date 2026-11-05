package com.studentforum;

import com.studentforum.repository.AdminStudyRepository;
import com.studentforum.repository.StudyPlanRepository;
import com.studentforum.repository.StudyRoomRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StudyRepositoryTest {
    private final JdbcTemplate db = mock(JdbcTemplate.class);

    @Test void planSearchBindsOwnerStatusAndTrimmedSubject() {
        new StudyPlanRepository(db).plans(7, "active", " 数学 ");
        verify(db).queryForList(contains("p.user_id=? AND p.status=? AND p.subject=?"), eq(7L), eq("active"), eq("数学"));
    }

    @Test void allPlansDoesNotAddAnAllStatusFilter() {
        new StudyPlanRepository(db).plans(7, "all", null);
        verify(db).queryForList(contains("ORDER BY p.status='active'"), eq(7L));
    }

    @Test void templatePrivacyAndSubjectAreBothBound() {
        var repository = new StudyPlanRepository(db);
        repository.ownedTemplates(7, " 数学 ");
        repository.sharedTemplates(7, " 数学 ");
        verify(db).queryForList(contains("t.owner_id=? AND subject=?"), eq(7L), eq("数学"));
        verify(db).queryForList(contains("t.owner_id<>? AND t.visibility='shared' AND subject=?"), eq(7L), eq("数学"));
    }

    @Test void roomListsKeepPublicAndMemberScopesSeparate() {
        var repository = new StudyRoomRepository(db);
        repository.rooms(null, "all");
        repository.rooms(7L, "joined");
        verify(db).queryForList(contains("r.visibility='public'"), eq(new Object[0]));
        verify(db).queryForList(contains("r.owner_id=? OR r.id IN"), eq(7L), eq(7L));
    }

    @Test void roomMessageLookupIsScopedToItsRoom() {
        new StudyRoomRepository(db).findRoomMessage(9, 4);
        verify(db).queryForList("SELECT * FROM study_room_messages WHERE id=? AND room_id=?", 9L, 4L);
    }

    @Test void adminFocusUsesTheSameStartBoundaryInAllBreakdowns() {
        var repository = new AdminStudyRepository(db);
        LocalDateTime start = LocalDateTime.of(2026, 11, 1, 0, 0);
        repository.focusSummary(start);
        repository.focusTrend(start);
        repository.focusSubjects(start);
        repository.focusSources(start);
        repository.focusLeaders(start);
        verify(db, times(5)).queryForList(contains("started_at>=?"), eq(start));
    }
}
