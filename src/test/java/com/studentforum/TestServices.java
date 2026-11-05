package com.studentforum;

import com.studentforum.mapper.ForumMapper;
import com.studentforum.repository.BadgeRepository;
import com.studentforum.repository.ForumRepository;
import com.studentforum.repository.MentionRepository;
import com.studentforum.repository.ReputationRepository;
import com.studentforum.service.AccountAccessService;
import com.studentforum.service.BadgeService;
import com.studentforum.service.ForumService;
import com.studentforum.service.NotificationService;
import com.studentforum.service.PointService;
import org.springframework.jdbc.core.JdbcTemplate;

final class TestServices {
    static BadgeService badges(ForumMapper mapper, JdbcTemplate db) {
        return new BadgeService(new BadgeRepository(db), notifications(mapper, db));
    }

    static PointService points(ForumMapper mapper, JdbcTemplate db) {
        return new PointService(mapper, new AccountAccessService(mapper), new ReputationRepository(db));
    }

    static NotificationService notifications(ForumMapper mapper, JdbcTemplate db) {
        return new NotificationService(mapper, new AccountAccessService(mapper), new MentionRepository(db));
    }

    static ForumService forum(ForumMapper mapper, JdbcTemplate db) {
        return new ForumService(mapper, new ForumRepository(db, mapper), new AccountAccessService(mapper),
                points(mapper, db), notifications(mapper, db));
    }
}
