package com.studentforum;

import com.studentforum.mapper.ForumMapper;
import com.studentforum.repository.ForumRepository;
import com.studentforum.repository.MentionRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ForumRepositoryTest {
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final ForumMapper mapper = mock(ForumMapper.class);
    private final ForumRepository forum = new ForumRepository(db, mapper);

    @Test void revisionsAndReplyQuotesKeepTheOriginalParameterOrder() {
        forum.revision(9, 7, "标题", "正文");
        forum.replyRevision(10, 7, "回答");
        forum.quote(10, 11);
        forum.attachment(9, "/uploads/test.txt");
        forum.cover(9, "/uploads/test.png");
        verify(db).update("INSERT INTO post_revisions(post_id,editor_id,title,content) VALUES(?,?,?,?)", 9L, 7L, "标题", "正文");
        verify(db).update("INSERT INTO reply_revisions(reply_id,editor_id,content) VALUES(?,?,?)", 10L, 7L, "回答");
        verify(db).update("UPDATE replies SET quote_reply_id=? WHERE id=?", 11L, 10L);
        verify(db).update("UPDATE posts SET attachment_path=? WHERE id=?", "/uploads/test.txt", 9L);
        verify(db).update("UPDATE posts SET cover_path=? WHERE id=?", "/uploads/test.png", 9L);
    }

    @Test void postDeletionClearsReplyReferencesBeforeDeletingReplies() {
        forum.deletePost(9);
        var order = inOrder(db, mapper);
        order.verify(db).update("DELETE FROM reply_revisions WHERE reply_id IN (SELECT id FROM replies WHERE post_id=?)", 9L);
        order.verify(db).update("DELETE FROM mentions WHERE (target_type='post' AND target_id=?) OR (target_type='reply' AND target_id IN (SELECT id FROM replies WHERE post_id=?))", 9L, 9L);
        order.verify(db).update("DELETE FROM replies WHERE post_id=?", 9L);
        order.verify(db).update("DELETE FROM post_tags WHERE post_id=?", 9L);
        order.verify(db).update("DELETE FROM favorites WHERE post_id=?", 9L);
        order.verify(mapper).deletePost(9);
        verify(db, never()).update(contains("DELETE FROM point_logs"), any(Object[].class));
        verify(db, never()).update(contains("DELETE FROM admin_logs"), any(Object[].class));
    }

    @Test void replySortNeverInterpolatesArbitraryInput() {
        forum.replies(9, "r.id; DROP TABLE users");
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db).queryForList(sql.capture(), eq(9L));
        assertTrue(sql.getValue().endsWith("r.created_at ASC LIMIT 200"));
        assertFalse(sql.getValue().contains("DROP TABLE"));
    }

    @Test void messageMentionChecksBothConversationParticipantsUsingBoundParameters() {
        var mentions = new MentionRepository(db);
        assertFalse(mentions.messageParticipant(9, 7));
        verify(db).queryForList("SELECT 1 FROM messages m JOIN conversations c ON c.id=m.conversation_id WHERE m.id=? AND (c.user_a_id=? OR c.user_b_id=?)", 9L, 7L, 7L);
    }

    @Test void missingReportTargetsAreReturnedAsMissingInsteadOfIndexErrors() {
        assertNull(forum.resourceAuthor(99));
        assertNull(forum.messageAuthor(99));
        when(db.queryForList("SELECT sender_id FROM messages WHERE id=?", 99L)).thenReturn(List.of(Map.of("sender_id", 7L)));
        assertEquals(7L, forum.messageAuthor(99).get("sender_id"));
    }
}
