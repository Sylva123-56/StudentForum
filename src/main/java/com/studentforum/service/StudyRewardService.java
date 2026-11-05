package com.studentforum.service;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StudyRewardService {
    private final PointService points;
    private final BadgeService badges;

    public StudyRewardService(PointService points, BadgeService badges) {
        this.points = points;
        this.badges = badges;
    }

    @Transactional
    public List<Map<String, Object>> award(long userId, String action, int amount,
                                          String refType, Long refId, int dailyCap) {
        if (amount > 0) points.reward(userId, action, amount, refType, refId, dailyCap);
        return badges.evaluateBadges(userId);
    }
}
