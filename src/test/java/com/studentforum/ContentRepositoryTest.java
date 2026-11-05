package com.studentforum;

import com.studentforum.repository.AppealRepository;
import com.studentforum.repository.DraftRepository;
import com.studentforum.repository.VoteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContentRepositoryTest {
    private final JdbcTemplate db = mock(JdbcTemplate.class);

    @Test void votingKeepsItsDatabaseRowLock() {
        new VoteRepository(db).lockVote(4);
        verify(db).queryForList("SELECT * FROM votes WHERE post_id=? FOR UPDATE", 4L);
    }

    @Test void hiddenVoteResultsCannotLeakCounts() {
        new VoteRepository(db).voteOptions(4, false);
        verify(db).queryForList(contains("0 ballots FROM vote_options"), eq(4L));
    }

    @Test void bountyDebitStillRequiresSufficientBalance() {
        new VoteRepository(db).deductBounty(20, 7);
        verify(db).update("UPDATE users SET points=points-? WHERE id=? AND points>=?", 20L, 7L, 20L);
    }

    @Test void draftsAreReadAndDeletedWithinOwnerScope() {
        var drafts = new DraftRepository(db);
        drafts.ownedDraft(4, 7);
        drafts.deleteDraft(4, 7);
        verify(db).queryForList("SELECT * FROM drafts WHERE id=? AND user_id=?", 4L, 7L);
        verify(db).update("DELETE FROM drafts WHERE id=? AND user_id=?", 4L, 7L);
    }

    @Test void appealSearchKeepsUsernameBindingAndTwentyOneRowPrefetch() {
        new AppealRepository(db).adminAppeals("小明", 20);
        verify(db).queryForList(contains("ORDER BY (a.status='pending') DESC,a.id DESC LIMIT 21 OFFSET 20"), eq("小明"));
    }

    @Test void onlyOneWorkerCanClaimADueDraft() {
        when(db.update(anyString(), eq(4L))).thenReturn(1, 0);
        var drafts = new DraftRepository(db);
        assertTrue(drafts.claimDue(4));
        assertFalse(drafts.claimDue(4));
        verify(db, times(2)).update("UPDATE drafts SET scheduled_at=NULL WHERE id=? AND scheduled_at<=NOW()", 4L);
    }
}
