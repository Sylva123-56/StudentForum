package com.studentforum;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

class V2ControllerTest {
    private JdbcTemplate db;
    private V2Controller controller;
    private Authentication auth;

    @BeforeEach void setup() {
        db = mock(JdbcTemplate.class);
        controller = new V2Controller(db, new ForumService(mock(ForumMapper.class), db), mock(ForumMapper.class));
        auth = new UsernamePasswordAuthenticationToken("7", null, List.of());
    }

    @Test void searchesMessageRecipientsByTrimmedUsername() {
        List<Map<String, Object>> matches = List.of(Map.of("id", 8L, "username", "小明", "unavailable_reason", "对方仅接收关注者私信"));
        when(db.queryForList(anyString(), any(Object[].class))).thenReturn(matches);

        assertEquals(matches, controller.messageRecipients(auth, "  明  "));
        verify(db).queryForList(argThat(sql -> sql.contains("LOCATE(LOWER(?),LOWER(u.username))>0")
            && sql.contains("unavailable_reason") && sql.contains("user_blocks")
            && sql.contains("message_privacy") && sql.contains("LIMIT 20")),
            eq(7L), eq(7L), eq(7L), eq(7L), eq("明"), eq("明"), eq("明"));
    }

    @Test void rejectsShortSearchWithoutQueryingDatabase() {
        assertThrows(ResponseStatusException.class, () -> controller.messageRecipients(auth, "  "));
        verifyNoInteractions(db);
    }
}
