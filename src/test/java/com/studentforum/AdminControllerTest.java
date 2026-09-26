package com.studentforum;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

class AdminControllerTest {
    private ForumMapper mapper;
    private AdminController controller;
    private Authentication admin;

    @BeforeEach void setup() {
        mapper = mock(ForumMapper.class);
        controller = new AdminController(mapper, new ForumService(mapper, null));
        admin = new UsernamePasswordAuthenticationToken("1", null, List.of());
        when(mapper.user(1)).thenReturn(Map.of("id", 1L, "role", "admin", "status", "active"));
    }

    @Test void editsBoardWithTrimmedFields() {
        when(mapper.adminBoard(4)).thenReturn(Map.of("id", 4L, "name", "旧板块", "status", "enabled"));
        controller.editBoard(admin, 4, Map.of("name", "  学习经验  ", "slug", " study-notes ", "description", " 讨论学习方法 ", "sortOrder", 3));
        verify(mapper).editBoard(4, "学习经验", "study-notes", "讨论学习方法", 3);
    }

    @Test void rejectsDuplicateBoardSlug() {
        when(mapper.adminBoard(4)).thenReturn(Map.of("id", 4L, "name", "旧板块", "status", "enabled"));
        when(mapper.boardSlugTaken("study-notes", 4)).thenReturn(1);
        assertThrows(ResponseStatusException.class, () -> controller.editBoard(admin, 4, Map.of("name", "学习经验", "slug", "study-notes")));
        verify(mapper, never()).editBoard(anyLong(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test void restoresDisabledBoard() {
        when(mapper.adminBoard(4)).thenReturn(Map.of("id", 4L, "name", "旧板块", "status", "disabled"));
        when(mapper.enableBoard(4)).thenReturn(1);
        controller.boardStatus(admin, 4, Map.of("status", "enabled"));
        verify(mapper).enableBoard(4);
        verify(mapper).adminLog(1L, "enable", "board", 4L, "旧板块");
    }

    @Test void rejectsUnchangedTagStatus() {
        when(mapper.adminTag(9)).thenReturn(Map.of("id", 9L, "name", "算法", "status", "enabled"));
        assertThrows(ResponseStatusException.class, () -> controller.tagStatus(admin, 9, Map.of("status", "enabled")));
        verify(mapper, never()).disableTag(anyLong());
    }

    @Test void rejectsDuplicateTagName() {
        when(mapper.adminTag(9)).thenReturn(Map.of("id", 9L, "name", "算法", "status", "enabled"));
        when(mapper.tagNameTaken("数据结构", 9)).thenReturn(1);
        assertThrows(ResponseStatusException.class, () -> controller.editTag(admin, 9, Map.of("name", "数据结构")));
        verify(mapper, never()).editTag(anyLong(), anyString());
    }

    @SuppressWarnings("unchecked")
    @Test void pagesUsersWithFuzzyNicknameSearch() {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int index = 0; index < 21; index++) rows.add(Map.of("id", (long) index));
        when(mapper.users("小明", 21, 20)).thenReturn(rows);

        Map<String, Object> result = controller.users(admin, "  小明  ", 2);

        assertEquals(true, result.get("hasNext"));
        assertEquals(20, ((List<Map<String, Object>>) result.get("items")).size());
    }

    @Test void blankKeywordBecomesNull() {
        when(mapper.adminTags(isNull(), eq(21), eq(0))).thenReturn(List.of(Map.of("id", 1L)));

        Map<String, Object> result = controller.tagList(admin, "   ", 1);

        assertEquals(false, result.get("hasNext"));
        verify(mapper).adminTags(null, 21, 0);
    }

    @Test void rejectsInvalidPage() {
        assertThrows(ResponseStatusException.class, () -> controller.pointLogs(admin, null, 0));
        verify(mapper, never()).adminLogs(any(), anyInt(), anyInt());
    }

    @Test void moderatorPostsAreLimitedToOwnBoard() {
        when(mapper.user(1)).thenReturn(Map.of("id", 1L, "role", "moderator", "status", "active", "moderator_board_id", 3L));
        when(mapper.adminPosts(isNull(), eq(List.of(3L)), eq(21), eq(0))).thenReturn(List.of(Map.of("id", 7L)));

        controller.posts(admin, null, 1);

        verify(mapper).adminPosts(null, List.of(3L), 21, 0);
    }

    @Test void moderatorReportsAreFilteredBeforePagination() {
        when(mapper.user(1)).thenReturn(Map.of("id", 1L, "role", "moderator", "status", "active", "moderator_board_id", 3L));
        when(mapper.reports("垃圾", 3L, 21, 20)).thenReturn(List.of(Map.of("id", 7L)));

        Map<String, Object> result = controller.reports(admin, " 垃圾 ", 2);

        assertEquals(false, result.get("hasNext"));
        verify(mapper).reports("垃圾", 3L, 21, 20);
    }

    @Test void assignsModeratorBoard() {
        when(mapper.board(3)).thenReturn(Map.of("id", 3L, "name", "学习经验"));
        when(mapper.user(6)).thenReturn(Map.of("id", 6L, "role", "student", "status", "active"));

        controller.role(admin, 6, Map.of("role", "moderator", "boardId", 3));

        verify(mapper).userRole(6, "moderator", 3L);
        verify(mapper).adminLog(1L, "role", "user", 6L, "moderator · 学习经验");
    }

    @Test void moderatorRoleRequiresBoard() {
        assertThrows(ResponseStatusException.class, () -> controller.role(admin, 6, Map.of("role", "moderator")));
        verify(mapper, never()).userRole(anyLong(), anyString(), any());
    }

    @Test void switchingToStudentClearsBoard() {
        when(mapper.user(6)).thenReturn(Map.of("id", 6L, "role", "moderator", "status", "active"));

        controller.role(admin, 6, Map.of("role", "student", "boardId", 3));

        verify(mapper).userRole(6, "student", null);
    }

    @Test void keepsAtLeastOneAdmin() {
        when(mapper.user(6)).thenReturn(Map.of("id", 6L, "role", "admin", "status", "active"));
        when(mapper.adminCount()).thenReturn(1);

        assertThrows(ResponseStatusException.class, () -> controller.role(admin, 6, Map.of("role", "student")));
        verify(mapper, never()).userRole(anyLong(), anyString(), any());
    }
}
