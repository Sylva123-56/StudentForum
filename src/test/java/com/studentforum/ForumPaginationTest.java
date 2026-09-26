package com.studentforum;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

class ForumPaginationTest {
    private ForumMapper mapper;
    private ForumController controller;
    private Authentication auth;

    @BeforeEach void setup() {
        mapper = mock(ForumMapper.class);
        controller = new ForumController(mapper, new ForumService(mapper, null), null, null);
        auth = new UsernamePasswordAuthenticationToken("7", null, List.of());
    }

    @Test void searchRequestsOneExtraPostAndTrimsTitleKeyword() {
        controller.search("  学习  ", null, null, null, null, null, "latest", 2);
        verify(mapper).posts(isNull(), isNull(), isNull(), isNull(), isNull(), eq("学习"), eq("latest"), eq(21), eq(20));
    }

    @Test void postSearchUsesLiteralTitleSubstring() throws Exception {
        Method method = ForumMapper.class.getMethod("posts", Long.class, Long.class, String.class, Boolean.class, Boolean.class, String.class, String.class, int.class, int.class);
        String sql = method.getAnnotation(Select.class).value()[0];
        assertTrue(sql.contains("LOCATE(LOWER(#{keyword}),LOWER(p.title))"));
        assertFalse(sql.contains("p.content LIKE"));
    }

    @Test void notificationPagesHaveExactNextPageFlag() {
        when(mapper.notifications(7, 21, 20)).thenReturn(new ArrayList<>(List.of(Map.of("id", 1L), Map.of("id", 2L))));
        Map<String, Object> result = controller.notifications(auth, 2);
        assertEquals(false, result.get("hasNext"));
        assertEquals(2, ((List<?>) result.get("items")).size());

        List<Map<String, Object>> fullPage = new ArrayList<>();
        for (int index = 0; index < 21; index++) fullPage.add(Map.of("id", index));
        when(mapper.notifications(7, 21, 0)).thenReturn(fullPage);
        result = controller.notifications(auth, 1);
        assertEquals(true, result.get("hasNext"));
        assertEquals(20, ((List<?>) result.get("items")).size());
    }

    @Test void invalidNotificationPageDoesNotQueryDatabase() {
        assertThrows(ResponseStatusException.class, () -> controller.notifications(auth, 0));
        verifyNoInteractions(mapper);
    }
}
