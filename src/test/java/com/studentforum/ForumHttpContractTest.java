package com.studentforum;

import com.studentforum.controller.ForumController;
import com.studentforum.exception.ApiErrors;
import com.studentforum.mapper.ForumMapper;
import com.studentforum.repository.ForumRepository;
import com.studentforum.service.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ForumHttpContractTest {
    private final ForumMapper mapper = mock(ForumMapper.class);
    private final ForumRepository posts = mock(ForumRepository.class);
    private final Authentication auth = new UsernamePasswordAuthenticationToken("7", null, List.of());
    private MockMvc http;

    @BeforeEach void setup() {
        ForumService forum = TestServices.forum(mapper, null);
        http = MockMvcBuilders.standaloneSetup(new ForumController(forum, new AccountService(mapper,new AccountAccessService(mapper),TestServices.points(mapper,null),null),
                new PostEditService(mapper, forum, new com.studentforum.service.AccountAccessService(mapper)), new ForumQueryService(mapper, new AccountAccessService(mapper), posts),
                TestServices.notifications(mapper, null), TestServices.points(mapper, null), null))
                .setControllerAdvice(new ApiErrors()).build();
    }

    @Test void searchPreservesArrayFiltersAndLookaheadLimit() throws Exception {
        when(mapper.posts(2L, 3L, "question", true, false, "学习", "hot", 21, 20))
                .thenReturn(List.of(Map.of("id", 9L)));
        http.perform(get("/api/search").param("q", "  学习  ").param("board", "2").param("tag", "3")
                .param("type", "question").param("solved", "true").param("featured", "false")
                .param("sort", "hot").param("page", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(9));
    }

    @Test void invalidSearchDoesNotReachPersistence() throws Exception {
        http.perform(get("/api/search").param("page", "0")).andExpect(status().isBadRequest());
        http.perform(get("/api/search").param("type", "unknown")).andExpect(status().isBadRequest());
        http.perform(get("/api/search").param("q", "长".repeat(101))).andExpect(status().isBadRequest());
        verifyNoInteractions(mapper, posts);
    }

    @Test void missingBoardAndTagDoNotSearchPosts() throws Exception {
        when(mapper.board(99)).thenReturn(null);
        when(mapper.tag(99)).thenReturn(null);
        http.perform(get("/api/boards/99/posts")).andExpect(status().isNotFound());
        http.perform(get("/api/tags/99/posts")).andExpect(status().isNotFound());
        verify(mapper, never()).posts(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test void publishedPostKeepsTagsAndAuthenticatedFavoriteFlag() throws Exception {
        when(mapper.post(9)).thenAnswer(call -> new HashMap<>(Map.of("id", 9L, "status", "published")));
        when(mapper.postTags(9)).thenReturn(List.of(Map.of("id", 3L)));
        when(mapper.isFavorite(7, 9)).thenReturn(1);
        http.perform(get("/api/posts/9").principal(auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.tags[0].id").value(3)).andExpect(jsonPath("$.favorited").value(true));
        http.perform(get("/api/posts/9")).andExpect(status().isOk()).andExpect(jsonPath("$.favorited").value(false));
        verify(mapper, times(2)).view(9);
        verify(mapper, times(1)).isFavorite(7, 9);
    }

    @Test void unpublishedPostsCannotBeReadListedOrFavorited() throws Exception {
        when(mapper.post(9)).thenReturn(Map.of("id", 9L, "status", "hidden"));
        http.perform(get("/api/posts/9")).andExpect(status().isNotFound());
        http.perform(get("/api/posts/9/replies")).andExpect(status().isNotFound());
        http.perform(post("/api/posts/9/favorite").principal(auth)).andExpect(status().isNotFound());
        verify(mapper, never()).view(anyLong());
        verify(mapper, never()).favorite(anyLong(), anyLong());
        verifyNoInteractions(posts);
    }

    @Test void replySortIsValidatedBeforeRepositoryAccess() throws Exception {
        when(mapper.post(9)).thenReturn(Map.of("id", 9L, "status", "published"));
        http.perform(get("/api/posts/9/replies").param("sort", "bad")).andExpect(status().isBadRequest());
        verifyNoInteractions(posts);
        when(posts.replies(9, "hot")).thenReturn(List.of(Map.of("id", 10L)));
        http.perform(get("/api/posts/9/replies").param("sort", "hot")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(10));
    }

    @Test void onlyAuthorsCanDeleteTheirUnacceptedReply() throws Exception {
        when(mapper.reply(9)).thenReturn(Map.of("author_id", 8L, "is_accepted", false));
        http.perform(delete("/api/replies/9").principal(auth)).andExpect(status().isForbidden());
        when(mapper.reply(9)).thenReturn(Map.of("author_id", 7L, "is_accepted", true));
        http.perform(delete("/api/replies/9").principal(auth)).andExpect(status().isForbidden());
        verify(mapper, never()).deleteReply(9);
        when(mapper.reply(9)).thenReturn(Map.of("author_id", 7L, "is_accepted", false));
        http.perform(delete("/api/replies/9").principal(auth)).andExpect(status().isOk());
        verify(mapper).deleteReply(9);
    }

    @Test void notificationEnvelopeAndReadOperationsStayUserScoped() throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        for (long id = 0; id < 21; id++) items.add(Map.of("id", id));
        when(mapper.notifications(7, 21, 20)).thenReturn(items);
        when(mapper.unread(7)).thenReturn(4);
        http.perform(get("/api/notifications").param("page", "2").principal(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.hasNext").value(true)).andExpect(jsonPath("$.unread").value(4));
        http.perform(patch("/api/notifications/99/read").principal(auth)).andExpect(status().isOk());
        http.perform(post("/api/notifications/read-all").principal(auth)).andExpect(status().isOk());
        verify(mapper).read(7, 99);
        verify(mapper).readAll(7);
    }

    @Test void publicProfileDoesNotLeakPrivacyFlagsOrHiddenFields() throws Exception {
        Map<String, Object> profile = Map.of("id", 7L, "school", "私密学校", "grade", "高一",
                "public_school", false, "public_grade", true);
        when(mapper.publicUser(7)).thenReturn(profile);
        http.perform(get("/api/users/7")).andExpect(status().isOk())
                .andExpect(jsonPath("$.school").doesNotExist()).andExpect(jsonPath("$.grade").value("高一"))
                .andExpect(jsonPath("$.public_school").doesNotExist()).andExpect(jsonPath("$.public_grade").doesNotExist());
        assertEquals("私密学校", profile.get("school"));
    }

    @Test void pointsEndpointKeepsExistingLevelThresholds() throws Exception {
        when(mapper.points(7)).thenReturn(200);
        http.perform(get("/api/me/points").principal(auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.points").value(200)).andExpect(jsonPath("$.level").value(3));
    }
    @Test void currentAccountEndpointKeepsTheOriginalProfileFields() throws Exception {
        when(mapper.user(7L)).thenReturn(Map.of("id",7L,"username","知识作者","role","student","status","active","points",42));
        http.perform(get("/api/me").principal(auth)).andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(7)).andExpect(jsonPath("$.username").value("知识作者"))
            .andExpect(jsonPath("$.role").value("student")).andExpect(jsonPath("$.status").value("active"))
            .andExpect(jsonPath("$.points").value(42));
    }
}
