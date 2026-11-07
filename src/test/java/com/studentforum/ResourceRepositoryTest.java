package com.studentforum;

import com.studentforum.repository.ResourceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResourceRepositoryTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final ResourceRepository repository=new ResourceRepository(db);

    @Test void publicSearchBindsKeywordsFavoritesAndPagination() {
        repository.search(" 导数 ","","","","",false,true,7,20);
        verify(db).queryForList(argThat(sql -> sql.contains("r.status='published'")
                && sql.contains("f.user_id=?") && sql.endsWith("LIMIT 21 OFFSET ?")),
                eq("导数"),eq("导数"),eq("导数"),eq(7L),eq(20));
    }
    @Test void ownSearchIncludesPendingResourcesButOnlyForTheirUploader() {
        repository.search("","","","","",true,false,7,0);
        verify(db).queryForList(contains("WHERE r.uploader_id=?"),eq(7L),eq(0));
    }
    @Test void uploadClaimAndDownloadRetainTheirRowLocks() {
        repository.lockUnusedUpload(4,7);
        repository.lockResource(9);
        verify(db).queryForList("SELECT * FROM resource_uploads WHERE id=? AND user_id=? AND resource_id IS NULL FOR UPDATE",4L,7L);
        verify(db).queryForList("SELECT * FROM resources WHERE id=? FOR UPDATE",9L);
    }
    @Test void downloadChargeIsGuardedAndLogKeepsTheNegativeAmount() {
        repository.chargeDownload(3,7);
        repository.recordDownloadCharge(3,9,7);
        verify(db).update("UPDATE users SET points=points-? WHERE id=? AND points>=?",3L,7L,3L);
        verify(db).update(contains("SELECT id,'resource_download',?,points,'resource',?"),eq(-3L),eq(9L),eq(7L));
    }
    @Test void fileLookupIsScopedToItsResource() {
        repository.file(4,9);
        verify(db).queryForList("SELECT * FROM resource_files WHERE id=? AND resource_id=?",4L,9L);
    }
    @Test void interactionTableComesFromClosedChoices() {
        repository.toggleInteraction(9,7,true,true);
        repository.toggleInteraction(9,7,false,false);
        verify(db).update("INSERT IGNORE INTO resource_favorites(resource_id,user_id) VALUES(?,?)",9L,7L);
        verify(db).update("DELETE FROM resource_votes WHERE resource_id=? AND user_id=?",9L,7L);
    }
}
