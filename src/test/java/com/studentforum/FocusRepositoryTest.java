package com.studentforum;

import com.studentforum.repository.FocusRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FocusRepositoryTest {
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final FocusRepository focus = new FocusRepository(db);

    @Test void leaderboardRetainsTheExtraRowAndStartDateTwice() {
        LocalDateTime start = LocalDateTime.of(2026, 10, 27, 0, 0);
        focus.leaderboard(start, 21, 20);
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db).queryForList(sql.capture(), eq(start), eq(start), eq(21), eq(20));
        assertTrue(sql.getValue().contains("u.status='active'"));
        assertTrue(sql.getValue().contains("ORDER BY range_minutes DESC"));
    }

    @Test void historyAndSummaryKeepTheExactUserAndDateBounds() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 3, 0, 0);
        focus.sessions(7, start, 20);
        focus.summary(7, start, end);
        verify(db).queryForList(contains("WHERE f.user_id=? AND f.started_at>=?"), eq(7L), eq(start), eq(20));
        verify(db).queryForList(contains("WHERE user_id=? AND started_at>=? AND started_at<?"), eq(7L), eq(start), eq(end));
    }

    @Test void privateRoomMembershipLookupUsesBothBoundKeys() {
        assertFalse(focus.isMember(9, 7));
        verify(db).queryForList("SELECT id FROM study_room_members WHERE room_id=? AND user_id=?", 9L, 7L);
    }

    @Test void sessionInsertionReadsNewIdAfterWriting() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 8, 0);
        LocalDateTime end = start.plusMinutes(25);
        when(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class)).thenReturn(31L);
        assertEquals(31L, focus.insertSession(7, "数学", "刷题", "", 25, start, end, null, null, null, "timer"));
        var order = inOrder(db);
        order.verify(db).update(startsWith("INSERT INTO focus_sessions("), eq(7L), eq("数学"), eq("刷题"), eq(""), eq(25),
                eq(start), eq(end), isNull(), isNull(), isNull(), eq("timer"));
        order.verify(db).queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
