package com.studentforum;

import com.studentforum.repository.CheckinRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CheckinRepositoryTest {
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final CheckinRepository checkins = new CheckinRepository(db);

    @Test void guestOnlySeesActivePublicGoals() {
        checkins.publicGoals(null, "数学");
        var query = org.mockito.ArgumentCaptor.forClass(String.class);
        var args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(db).queryForList(query.capture(), args.capture());
        assertTrue(query.getValue().contains("g.status='active' AND u.status='active'"));
        assertTrue(query.getValue().contains("g.visibility='public'"));
        assertFalse(query.getValue().contains("g.visibility='group'"));
        assertArrayEquals(new Object[]{"数学"}, args.getValue());
    }

    @Test void signedInMembersHaveOnlyTheirGroupsAddedToThePublicScope() {
        checkins.publicGoals(7L, " 数学 ");
        var query = org.mockito.ArgumentCaptor.forClass(String.class);
        var args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(db).queryForList(query.capture(), args.capture());
        assertTrue(query.getValue().contains("g.visibility='group' AND g.group_id IN (SELECT group_id FROM study_group_members WHERE user_id=?)"));
        assertArrayEquals(new Object[]{7L, "数学"}, args.getValue());
    }

    @Test void recordFeedNeverDropsOwnerFilterWhenSelectingOneGoal() {
        checkins.records(7, 9L, 20);
        var query = org.mockito.ArgumentCaptor.forClass(String.class);
        var args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(db).queryForList(query.capture(), args.capture());
        assertTrue(query.getValue().contains("WHERE c.user_id=? AND c.goal_id=?"));
        assertArrayEquals(new Object[]{7L, 9L, 20}, args.getValue());
    }

    @Test void archivingUsesBoundValuesNotInterpolatedStatus() {
        checkins.setGoalStatus("archived", 9L);
        verify(db).update("UPDATE checkin_goals SET status=? WHERE id=?", "archived", 9L);
    }

    @Test void insertKeepsSourceAndVisibilityValuesAfterNormalization() {
        when(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class)).thenReturn(42L);
        long id = checkins.insertCheckin(7, 9, "记录", 25, "数学", null, "private",
                LocalDate.of(2026, 11, 2), false, "manual", null, null);
        assertEquals(42L, id);
        verify(db).update(startsWith("INSERT INTO checkins("), eq(7L), eq(9L), eq("记录"), eq(25), eq("数学"),
                isNull(), eq("private"), eq(LocalDate.of(2026, 11, 2)), eq(false), eq("manual"), isNull(), isNull());
    }
}
