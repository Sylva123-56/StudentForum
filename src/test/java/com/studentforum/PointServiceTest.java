package com.studentforum;

import com.studentforum.mapper.ForumMapper;
import com.studentforum.repository.ReputationRepository;
import com.studentforum.service.AccountAccessService;
import com.studentforum.service.PointService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PointServiceTest {
    private final ForumMapper mapper = mock(ForumMapper.class);
    private final ReputationRepository reputation = mock(ReputationRepository.class);
    private final PointService points = new PointService(mapper, new AccountAccessService(mapper), reputation);

    @Test void dailyRewardLimitPreventsBothBalanceAndLedgerWrites() {
        when(mapper.dailyPoints(7, "login")).thenReturn(1);
        points.reward(7, "login", 1, "user", 7L, 1);
        verify(mapper, never()).addPoints(anyLong(), anyInt());
        verify(mapper, never()).pointLog(anyLong(), anyString(), anyInt(), anyInt(), anyString(), any());
    }

    @Test void rewardsLogTheBalanceAfterTheCredit() {
        when(mapper.points(7)).thenReturn(12);
        points.reward(7, "accepted", 10, "reply", 9L, 0);
        var order = inOrder(mapper);
        order.verify(mapper).addPoints(7, 10);
        order.verify(mapper).points(7);
        order.verify(mapper).pointLog(7, "accepted", 10, 12, "reply", 9L);
        verify(mapper, never()).dailyPoints(anyLong(), anyString());
    }

    @Test void reputationLedgerUsesTheClampedStoredBalance() {
        when(mapper.user(7)).thenReturn(Map.of("reputation", 200));
        points.changeReputation(7, 8, 2, "回答被采纳");
        var order = inOrder(reputation, mapper);
        order.verify(reputation).change(7, 2);
        order.verify(mapper).user(7);
        order.verify(reputation).log(7, 8, 2, 200, "回答被采纳");
    }
}
