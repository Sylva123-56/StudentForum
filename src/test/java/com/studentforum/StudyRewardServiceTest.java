package com.studentforum;

import com.studentforum.service.BadgeService;
import com.studentforum.service.PointService;
import com.studentforum.service.StudyRewardService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudyRewardServiceTest {
    private final PointService points = mock(PointService.class);
    private final BadgeService badges = mock(BadgeService.class);
    private final StudyRewardService rewards = new StudyRewardService(points, badges);

    @Test void zeroPointActionsStillEvaluateBadgesWithoutWritingPoints() {
        var earned = List.of(Map.<String, Object>of("code", "plan_complete"));
        when(badges.evaluateBadges(7)).thenReturn(earned);
        assertSame(earned, rewards.award(7, "plan_complete", 0, "study_plan", 9L, 0));
        verifyNoInteractions(points);
    }

    @Test void pointCreditPrecedesBadgeEvaluation() {
        rewards.award(7, "focus", 2, "focus", 9L, 10);
        var order = inOrder(points, badges);
        order.verify(points).reward(7, "focus", 2, "focus", 9L, 10);
        order.verify(badges).evaluateBadges(7);
    }
}
