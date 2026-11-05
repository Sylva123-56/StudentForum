package com.studentforum;

import com.studentforum.repository.GroupRepository;
import com.studentforum.repository.SocialRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SocialGroupRepositoryTest {
    private final JdbcTemplate db = mock(JdbcTemplate.class);

    @Test void blocksCheckBothDirections() {
        new SocialRepository(db).blocksBetween(7, 8);
        verify(db).queryForList(contains("(user_id=? AND blocked_id=?) OR (user_id=? AND blocked_id=?)"), eq(7L), eq(8L), eq(8L), eq(7L));
    }

    @Test void messageReportsAreScopedToConversationMembership() {
        new SocialRepository(db).reportableMessage(9, 7);
        verify(db).queryForList(contains("m.id=? AND (c.user_a_id=? OR c.user_b_id=?)"), eq(9L), eq(7L), eq(7L));
    }

    @Test void groupPostsBindViewerTwiceButGuestQueriesHaveNoViewerParameters() {
        var groups = new GroupRepository(db);
        groups.posts(4, 7L, 20);
        groups.posts(4, null, 20);
        verify(db).queryForList(contains("AS liked,EXISTS"), eq(7L), eq(7L), eq(4L));
        verify(db).queryForList(contains("FALSE AS liked,FALSE AS favorited"), eq(4L));
    }

    @Test void groupCheckinStatusIsPersonalToTheViewer() {
        new GroupRepository(db).checkins(4, 7L);
        verify(db).queryForList(contains("r2.user_id=?"), eq(7L), eq(4L));
    }

    @Test void memberTotalsAreRecountedForOnlyTheTargetGroup() {
        new GroupRepository(db).refreshMemberCount(4);
        verify(db).update(contains("member_count=(SELECT COUNT(*)"), eq(4L), eq(4L));
    }
}
