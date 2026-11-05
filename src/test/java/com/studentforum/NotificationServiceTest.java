package com.studentforum;

import com.studentforum.mapper.ForumMapper;
import com.studentforum.repository.MentionRepository;
import com.studentforum.service.AccountAccessService;
import com.studentforum.service.NotificationService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class NotificationServiceTest {
    private final ForumMapper mapper = mock(ForumMapper.class);
    private final MentionRepository mentions = mock(MentionRepository.class);
    private final NotificationService notifications = new NotificationService(mapper, new AccountAccessService(mapper), mentions);

    @Test void privateMessageCannotMentionSomeoneOutsideTheConversation() {
        when(mentions.users("reader")).thenReturn(List.of(Map.of("id", 8L, "notify_mention", true)));
        notifications.mentions(7, "@reader", "message", 99, null);
        verify(mentions).messageParticipant(99, 8);
        verify(mentions, never()).insert(8, 7, "message", 99);
        verifyNoInteractions(mapper);
    }

    @Test void duplicateMentionsNotifyOnlyWhenInserted() {
        when(mentions.users("reader")).thenReturn(List.of(Map.of("id", 8L, "notify_mention", true)));
        when(mentions.insert(8, 7, "post", 99)).thenReturn(true, false);
        notifications.mentions(7, "@reader @reader", "post", 99, 99L);
        verify(mapper, times(1)).notifyUser(8, "mention", "有人在讨论中提及了你", 99L);
    }

    @Test void disabledMentionNotificationsStillRecordTheMention() {
        when(mentions.users("reader")).thenReturn(List.of(Map.of("id", 8L, "notify_mention", false)));
        when(mentions.insert(8, 7, "post", 99)).thenReturn(true);
        notifications.mentions(7, "@reader", "post", 99, 99L);
        verify(mentions).insert(8, 7, "post", 99);
        verifyNoInteractions(mapper);
    }
}
