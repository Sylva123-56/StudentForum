package com.studentforum;

import com.studentforum.mapper.ForumMapper;
import com.studentforum.service.ForumService;
import com.studentforum.service.PostEditService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PostEditServiceTest {
    @Test void nonOwnerCannotEdit() {
        ForumMapper mapper = mock(ForumMapper.class);
        ForumService forum = mock(ForumService.class);
        var auth = new UsernamePasswordAuthenticationToken("8", null);
        var row = Map.<String,Object>of("author_id", 7L, "status", "published", "type", "question");
        when(mapper.post(42L)).thenReturn(row);
        when(forum.require(row)).thenReturn(row);
        when(forum.number(row, "author_id")).thenReturn(7L);
        when(forum.id(auth)).thenReturn(8L);
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> new PostEditService(mapper, forum).edit(auth, 42L,
                        Map.of("title", "another title", "content", "another content")))
                .getStatusCode().value());
        verify(mapper, never()).editPost(anyLong(), anyString(), anyString());
    }

    @Test void writesRemainAtTheServiceTransactionBoundary() throws Exception {
        assertNotNull(PostEditService.class.getMethod("edit", org.springframework.security.core.Authentication.class, long.class, Map.class)
                .getAnnotation(Transactional.class));
        assertNotNull(PostEditService.class.getMethod("delete", org.springframework.security.core.Authentication.class, long.class)
                .getAnnotation(Transactional.class));
    }
}
