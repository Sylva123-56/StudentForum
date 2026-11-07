package com.studentforum;

import com.studentforum.repository.MistakeRepository;
import com.studentforum.repository.MistakeImageRepository;
import com.studentforum.repository.WikiRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeRepositoryTest {
    private final JdbcTemplate db = mock(JdbcTemplate.class);

    @Test void groupMistakeSearchRequiresAnActiveGroupMembership() {
        new MistakeRepository(db).search("group", " 数学 ", "导数", true, 7, 20);
        verify(db).queryForList(argThat(sql -> sql.contains("gm.user_id=? AND g.status='active'")
                && sql.contains("m.next_review_date<=CURRENT_DATE") && sql.contains("LIMIT 21 OFFSET ?")),
                eq(7L), eq("数学"), eq("导数"), eq("导数"), eq("导数"), eq(20));
    }

    @Test void publicMistakeSearchHasNoViewerParameter() {
        new MistakeRepository(db).search("public", "", "", false, 7, 0);
        verify(db).queryForList(contains("m.visibility='public'"), eq(0));
    }

    @Test void reviewAndWikiEditingRetainTheirRowLocks() {
        new MistakeRepository(db).lockMistake(4);
        new WikiRepository(db).lockPage(9);
        verify(db).queryForList("SELECT * FROM mistakes WHERE id=? FOR UPDATE", 4L);
        verify(db).queryForList("SELECT * FROM wiki_pages WHERE id=? FOR UPDATE", 9L);
    }

    @Test void privateImageMetadataRequiresBothImageAndOwner() {
        new MistakeImageRepository(db).ownedImage(4, 7);
        verify(db).queryForList("SELECT * FROM mistake_images WHERE id=? AND user_id=?", 4L, 7L);
    }

    @Test void moderatorWikiAdminQueryRemainsScopedToTheirBoard() {
        new WikiRepository(db).adminPages(false, 3, 20);
        verify(db).queryForList(contains("WHERE p.board_id=?"), eq(3L), eq(20));
    }
    @Test void sharedDetailQueriesBindIdsAndKeepTheirAuthorProjections() {
        var repository=new com.studentforum.repository.KnowledgeRepository(db);
        repository.resource(4);
        repository.note(5);
        repository.mistake(6);
        repository.collection(7);
        repository.column(8);
        repository.publishedPost(9);
        verify(db).queryForList("SELECT r.*,u.username FROM resources r JOIN users u ON u.id=r.uploader_id WHERE r.id=?",4L);
        verify(db).queryForList("SELECT n.*,u.username FROM notes n JOIN users u ON u.id=n.user_id WHERE n.id=?",5L);
        verify(db).queryForList("SELECT m.*,u.username FROM mistakes m JOIN users u ON u.id=m.user_id WHERE m.id=?",6L);
        verify(db).queryForList("SELECT c.*,u.username FROM collections c JOIN users u ON u.id=c.owner_id WHERE c.id=?",7L);
        verify(db).queryForList("SELECT c.*,u.username FROM columns c JOIN users u ON u.id=c.owner_id WHERE c.id=?",8L);
        verify(db).queryForList("SELECT id,title FROM posts WHERE id=? AND status='published'",9L);
    }
    @Test void sharedMembershipRequiresAnActiveGroup() {
        new com.studentforum.repository.KnowledgeRepository(db).activeMembership(99,7);
        verify(db).queryForList(contains("g.status='active'"),eq(99L),eq(7L));
    }
    @Test void auditBindsActorActionTargetAndDetailInOrder() {
        new com.studentforum.repository.KnowledgeRepository(db).audit(7,"resource_published","resource",4,"审核通过");
        verify(db).update("INSERT INTO admin_logs(actor_id,action,target_type,target_id,detail) VALUES(?,?,?,?,?)",7L,"resource_published","resource",4L,"审核通过");
    }
}
