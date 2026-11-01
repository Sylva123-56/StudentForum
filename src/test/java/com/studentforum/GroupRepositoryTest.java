package com.studentforum;

import com.studentforum.repository.GroupRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GroupRepositoryTest {
    @Test void searchPassesFiltersAsBoundValues() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        GroupRepository repository = new GroupRepository(jdbc);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of("id", 3L)));

        assertEquals(1, repository.search("  %' OR 1=1 --  ", " java ", null, null, "active", "members").size());
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        var args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sql.capture(), args.capture());
        assertTrue(sql.getValue().contains("g.member_count DESC"));
        assertFalse(sql.getValue().contains("OR 1=1"));
        assertArrayEquals(new Object[]{"active", "%' OR 1=1 --", "%' OR 1=1 --", "%' OR 1=1 --", "java"}, args.getValue());
    }

    @Test void invalidSortCannotBecomeSql() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        GroupRepository repository = new GroupRepository(jdbc);
        repository.rankings("invalid, DROP TABLE study_groups");
        verify(jdbc).queryForList(argThat(sql -> sql.contains("activity_score DESC") && !sql.contains("DROP TABLE")));
    }
}
