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
}
