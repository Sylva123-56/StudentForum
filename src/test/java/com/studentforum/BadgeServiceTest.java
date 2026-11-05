package com.studentforum;

import com.studentforum.repository.BadgeRepository;
import com.studentforum.service.BadgeService;
import com.studentforum.service.NotificationService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BadgeServiceTest {
    private final BadgeRepository repository = mock(BadgeRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final BadgeService badges = new BadgeService(repository, notifications);

    @Test void alreadyEarnedBadgesAreNotAnnouncedAgain() {
        when(repository.checkins(7)).thenReturn(1L);
        assertTrue(badges.evaluateBadges(7).isEmpty());
        verify(repository).insert(7, "checkin_first", "第一次打卡", "完成第一次学习打卡");
        verifyNoInteractions(notifications);
    }

    @Test void consecutiveGoalDatesAwardOnlyEligibleBadges() {
        when(repository.goals(7)).thenReturn(List.of(Map.of("id", 2L)));
        when(repository.checkinDates(2, 7)).thenReturn(List.of(Map.of("checkin_date", LocalDate.now()),
                Map.of("checkin_date", LocalDate.now().minusDays(1)), Map.of("checkin_date", LocalDate.now().minusDays(2))));
        when(repository.checkins(7)).thenReturn(3L);
        when(repository.insert(eq(7L), anyString(), anyString(), anyString())).thenReturn(true);
        var result = badges.evaluateBadges(7);
        assertEquals(List.of("checkin_first", "checkin_streak_3"), result.stream().map(row -> row.get("code")).toList());
        verify(notifications, times(2)).notifyUser(eq(7L), eq("badge"), anyString(), isNull());
        verify(repository, never()).insert(eq(7L), eq("checkin_streak_7"), anyString(), anyString());
    }

    @Test void catalogIncludesUnearnedEntriesAndEarnedDate() {
        when(repository.owned(7)).thenReturn(List.of(Map.of("code", "focus_first", "earned_at", "2026-11-02")));
        var catalog = badges.badgeCatalog(7);
        assertEquals(11, catalog.size());
        var earned = catalog.stream().filter(row -> "focus_first".equals(row.get("code"))).findFirst().orElseThrow();
        assertEquals(true, earned.get("earned"));
        assertEquals("2026-11-02", earned.get("earned_at"));
        assertEquals(false, catalog.getFirst().get("earned"));
    }
}
