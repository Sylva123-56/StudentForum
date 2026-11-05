package com.studentforum;

import com.studentforum.controller.V2ContentController;
import com.studentforum.service.ContentWorkflowService;
import com.studentforum.mapper.ForumMapper;
import com.studentforum.service.ForumService;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

class V2ContentControllerTest {
    private JdbcTemplate db;
    private ForumMapper mapper;
    private V2ContentController controller;
    private Authentication admin;

    @BeforeEach void setup() {
        db = mock(JdbcTemplate.class);
        mapper = mock(ForumMapper.class);
        var accounts = new com.studentforum.service.AccountAccessService(mapper);
        var votes = new com.studentforum.service.VoteService(new com.studentforum.repository.VoteRepository(db), accounts, mapper);
        var drafts = new com.studentforum.service.DraftService(new com.studentforum.repository.DraftRepository(db), accounts, mapper);
        var appeals = new com.studentforum.service.AppealService(new com.studentforum.repository.AppealRepository(db), accounts, TestServices.points(mapper, db), TestServices.notifications(mapper, db), mapper);
        controller = new V2ContentController(new ContentWorkflowService(new com.studentforum.repository.ContentRevisionRepository(db), TestServices.forum(mapper, db), votes, accounts, mapper), votes, drafts, appeals);
        admin = new UsernamePasswordAuthenticationToken("1", null, List.of());
        when(mapper.user(1)).thenReturn(Map.of("id", 1L, "role", "admin", "status", "active"));
        when(db.update(startsWith("UPDATE appeals SET"), any(), any(), any(), anyLong())).thenReturn(1);
    }

    @Test void approvingAccountAppealRestoresActiveStatus() {
        when(db.queryForList(eq("SELECT * FROM appeals WHERE id=?"), any(Object[].class)))
            .thenReturn(List.of(Map.of("user_id", 2L, "target_type", "user", "target_id", 2L)));

        controller.handleAppeal(admin, 5, Map.of("status", "resolved", "note", "核查通过"));

        verify(db).update("UPDATE users SET status='active' WHERE id=? AND status IN ('muted','banned')", 2L);
        verify(mapper).notifyUser(eq(2L), eq("appeal"), contains("账号已恢复"), isNull());
    }

    @Test void rejectingAccountAppealDoesNotRestoreStatus() {
        when(db.queryForList(eq("SELECT * FROM appeals WHERE id=?"), any(Object[].class)))
            .thenReturn(List.of(Map.of("user_id", 2L, "target_type", "user", "target_id", 2L)));

        controller.handleAppeal(admin, 5, Map.of("status", "rejected", "note", "不予通过"));

        verify(db, never()).update(startsWith("UPDATE users SET status='active'"), anyLong());
    }

    @Test void approvingOtherAppealDoesNotRestoreAccount() {
        when(db.queryForList(eq("SELECT * FROM appeals WHERE id=?"), any(Object[].class)))
            .thenReturn(List.of(Map.of("user_id", 2L, "target_type", "post", "target_id", 8L)));

        controller.handleAppeal(admin, 5, Map.of("status", "resolved", "note", "核查通过"));

        verify(db, never()).update(startsWith("UPDATE users SET status='active'"), anyLong());
    }
}
