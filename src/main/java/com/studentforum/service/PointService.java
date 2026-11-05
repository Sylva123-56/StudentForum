package com.studentforum.service;

import com.studentforum.mapper.ForumMapper;
import com.studentforum.repository.ReputationRepository;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PointService {
    private final ForumMapper mapper;
    private final AccountAccessService accounts;
    private final ReputationRepository reputation;

    public PointService(ForumMapper mapper, AccountAccessService accounts, ReputationRepository reputation) {
        this.mapper = mapper;
        this.accounts = accounts;
        this.reputation = reputation;
    }

    public Map<String, Object> points(Authentication auth) {
        int points = mapper.points(accounts.id(auth));
        return Map.of("points", points, "level", points >= 1000 ? 5 : points >= 500 ? 4 : points >= 200 ? 3 : points >= 50 ? 2 : 1);
    }

    public List<Map<String, Object>> logs(Authentication auth) {
        return mapper.pointLogs(accounts.id(auth));
    }

    @Transactional
    public void reward(long userId, String action, int amount, String refType, Long refId, int dailyCap) {
        if (dailyCap > 0 && mapper.dailyPoints(userId, action) >= dailyCap) return;
        mapper.addPoints(userId, amount);
        mapper.pointLog(userId, action, amount, mapper.points(userId), refType, refId);
    }

    @Transactional
    public void changeReputation(long user, long actor, int amount, String reason) {
        reputation.change(user, amount);
        Map<String, Object> profile = mapper.user(user);
        if (profile == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "内容不存在");
        reputation.log(user, actor, amount, ((Number) profile.get("reputation")).longValue(), reason);
    }
}
