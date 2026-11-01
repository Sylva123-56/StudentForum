package com.studentforum;

import com.studentforum.repository.NoteRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NoteRepositoryTest {
    @Test void publicSearchOnlyReturnsPublishedNotes() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        NoteRepository notes = new NoteRepository(jdbc);
        notes.search(false, 0, "学习", "数学", 20);
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        var args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sql.capture(), args.capture());
        assertTrue(sql.getValue().contains("n.status='published'"));
        assertArrayEquals(new Object[]{0L, "数学", "数学", "学习", "学习", "学习", "学习", 20}, args.getValue());
    }

    @Test void editingNeedsAnExistingLockedNote() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        NoteRepository notes = new NoteRepository(jdbc);
        when(jdbc.queryForList("SELECT id FROM notes WHERE id=? FOR UPDATE", 42L)).thenReturn(List.of(Map.of("id", 42L)));
        assertTrue(notes.lock(42L));
        assertFalse(notes.lock(43L));
    }
}
