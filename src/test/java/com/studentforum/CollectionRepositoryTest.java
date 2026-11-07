package com.studentforum;

import com.studentforum.repository.CollectionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CollectionRepositoryTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final CollectionRepository repository=new CollectionRepository(db);

    @Test void filtersBindBothInteractionUsersWithoutInterpolatingTheirIds() {
        repository.search("collections","导数",false,true,true,true,12345,20);
        verify(db).queryForList(argThat(sql -> sql.contains("c.status='active'")
            && sql.contains("c.is_featured=TRUE") && sql.contains("collection_favorites")
            && sql.contains("collection_follows") && !sql.contains("12345")
            && sql.endsWith("LIMIT 21 OFFSET ?")),
            eq(12345L),eq("导数"),eq("导数"),eq("导数"),eq(12345L),eq(12345L),eq(20));
    }
    @Test void columnSearchIgnoresCollectionOnlyFlags() {
        repository.search("columns","",true,true,true,true,7,0);
        verify(db).queryForList(argThat(sql -> sql.contains("FROM columns")
            && sql.contains("c.owner_id=?") && !sql.contains("is_featured")
            && !sql.contains("collection_favorites") && !sql.contains("collection_follows")),
            eq(7L),eq(""),eq(""),eq(""),eq(0));
    }
    @Test void onlyKnownTableNamesAreAllowed() {
        assertThrows(IllegalArgumentException.class,()->repository.search("users","",false,false,false,false,0,0));
        assertThrows(IllegalArgumentException.class,()->repository.update("users","title","description",null,1));
        verifyNoInteractions(db);
    }
    @Test void itemAndEpisodeWritesRetainParentRowLocks() {
        repository.lockCollection(4);
        repository.lockColumn(9);
        verify(db).queryForList("SELECT id FROM collections WHERE id=? FOR UPDATE",4L);
        verify(db).queryForList("SELECT id FROM columns WHERE id=? FOR UPDATE",9L);
    }
    @Test void episodeVisibilityIsLimitedToPublishedOrOwner() {
        repository.episodes(4,7,8);
        verify(db).queryForList("SELECT * FROM column_episodes WHERE column_id=? AND (status='published' OR ?=?) ORDER BY episode_no",4L,7L,8L);
    }
    @Test void itemChangesRemainScopedAndCountsComeFromTheirOwnParent() {
        repository.reorderItem(2,5,4);
        repository.removeItem(5,4);
        repository.recountItems(4);
        repository.recountFollowers(4);
        verify(db).update("UPDATE collection_items SET sort_order=? WHERE id=? AND collection_id=?",2L,5L,4L);
        verify(db).update("DELETE FROM collection_items WHERE id=? AND collection_id=?",5L,4L);
        verify(db).update("UPDATE collections SET item_count=(SELECT COUNT(*) FROM collection_items WHERE collection_id=?) WHERE id=?",4L,4L);
        verify(db).update("UPDATE collections SET follower_count=(SELECT COUNT(*) FROM collection_follows WHERE collection_id=?) WHERE id=?",4L,4L);
    }
}
