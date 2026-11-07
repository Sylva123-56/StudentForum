package com.studentforum.service;

import com.studentforum.repository.GroupRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GroupPostServiceTest {
    private final GroupRepository groups = mock(GroupRepository.class);
    private final AccountAccessService accounts = mock(AccountAccessService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final GroupAccessService access = new GroupAccessService(groups);
    private final GroupPostService posts = new GroupPostService(groups, accounts, access, notifications);
    private final Authentication auth = new UsernamePasswordAuthenticationToken("7", "", List.of());
    private final Map<String, Object> post = Map.of("id", 11L, "title", "Study", "content", "Algebra", "tags", "Math");

    @BeforeEach void setup() {
        when(accounts.id(auth)).thenReturn(7L);
        when(groups.findGroup(3)).thenReturn(List.of(Map.of("id", 3L, "visibility", "public")));
        when(groups.membership(3, 7)).thenReturn(List.of(Map.of("role", "member")));
        when(groups.posts(3, 7L, 1000)).thenReturn(List.of(post));
    }

    @Test void guestSearchKeepsTitleContentTagsAndListLimit() {
        when(groups.posts(3, null, 100)).thenReturn(List.of(post));
        for (String keyword : new String[]{null, " ", "STUDY", "ALGEBRA", "MATH"}) {
            assertEquals(List.of(post), posts.groupPosts(3, null, keyword));
        }
        assertTrue(posts.groupPosts(3, null, "missing").isEmpty());
        verify(groups, never()).membership(anyLong(), anyLong());
    }

    @Test void detailAddsRepliesWithoutMutatingTheRepositoryRow() {
        var replies = List.<Map<String, Object>>of(Map.of("content", "reply"));
        when(groups.replies(11)).thenReturn(replies);
        var result = posts.postDetail(3, 11, auth);
        assertEquals(11L, result.get("id"));
        assertSame(replies, result.get("replies"));
        assertFalse(post.containsKey("replies"));
        verify(groups).posts(3, 7L, 1000);
    }

    @Test void privateGroupDeniesOutsidersBeforeReadingPosts() {
        when(groups.findGroup(3)).thenReturn(List.of(Map.of("id", 3L, "visibility", "private")));
        when(groups.membership(3, 7)).thenReturn(List.of());
        assertStatus(403, () -> posts.postDetail(3, 11, auth));
        assertStatus(403, () -> posts.groupPosts(3, auth, ""));
        verify(groups, never()).posts(anyLong(), any(), anyInt());
        verify(groups, never()).replies(anyLong());
    }

    @Test void anotherGroupsPostIsNotFoundBeforeInteractionsOrModeration() {
        when(groups.posts(3, 7L, 1000)).thenReturn(List.of());
        when(groups.membership(3, 7)).thenReturn(List.of(Map.of("role", "owner")));
        assertStatus(404, () -> posts.postDetail(3, 11, auth));
        assertStatus(404, () -> posts.reply(auth, 3, 11, Map.of("content", "reply")));
        assertStatus(404, () -> posts.like(auth, 3, 11));
        assertStatus(404, () -> posts.favorite(auth, 3, 11));
        assertStatus(404, () -> posts.moderatePost(auth, 3, 11, Map.of("status", "hidden")));
        verify(groups, never()).insertReply(anyLong(), anyLong(), anyString());
        verify(groups, never()).likePost(anyLong(), anyLong());
        verify(groups, never()).favoritePost(anyLong(), anyLong());
        verify(groups, never()).setPostStatus(anyString(), anyLong(), anyLong());
        verify(groups, never()).replies(anyLong());
    }

    @Test void createTrimsFieldsAndKeepsActivityAndAudit() {
        when(groups.insertPost(3, 7, "Study", "Algebra", "/image", "Math")).thenReturn(11L);
        var result = posts.createPost(auth, 3, Map.of("title", " Study ", "content", " Algebra ", "imagePath", "/image", "tags", " Math "));
        assertEquals(11L, result.get("id"));
        assertTrue(result.containsKey("replies"));
        verify(groups).recordPostActivity(3);
        verify(groups).log(3, 7, "post", "post", 11, "Study");
    }

    @Test void invalidPostDoesNotWriteAnything() {
        for (var input : List.of(Map.of("title", "x", "content", "body"), Map.of("title", "x".repeat(161), "content", "body"), Map.of("title", "ok", "content", " "))) {
            assertStatus(400, () -> posts.createPost(auth, 3, Map.copyOf(input)));
        }
        verify(groups, never()).insertPost(anyLong(), anyLong(), anyString(), anyString(), any(), anyString());
        verify(groups, never()).recordPostActivity(anyLong());
    }

    @Test void allMemberWritesRejectOutsiders() {
        when(groups.membership(3, 7)).thenReturn(List.of());
        assertStatus(403, () -> posts.createPost(auth, 3, Map.of("title", "Study", "content", "body")));
        assertStatus(403, () -> posts.reply(auth, 3, 11, Map.of("content", "body")));
        assertStatus(403, () -> posts.like(auth, 3, 11));
        assertStatus(403, () -> posts.favorite(auth, 3, 11));
        verify(groups, never()).posts(anyLong(), any(), anyInt());
        verify(groups, never()).insertPost(anyLong(), anyLong(), anyString(), anyString(), any(), anyString());
        verify(groups, never()).insertReply(anyLong(), anyLong(), anyString());
        verify(groups, never()).likePost(anyLong(), anyLong());
        verify(groups, never()).favoritePost(anyLong(), anyLong());
    }

    @Test void replyKeepsActivityAndMentionTarget() {
        posts.reply(auth, 3, 11, Map.of("content", " @student reply "));
        verify(groups).insertReply(11, 7, "@student reply");
        verify(groups).recordReplyActivity(3);
        verify(notifications).mentions(7, "@student reply", "group_post", 11, null);
    }

    @Test void invalidReplyDoesNotWriteOrNotify() {
        for (String content : List.of(" ", "x".repeat(3001))) {
            assertStatus(400, () -> posts.reply(auth, 3, 11, Map.of("content", content)));
        }
        verify(groups, never()).insertReply(anyLong(), anyLong(), anyString());
        verify(groups, never()).recordReplyActivity(anyLong());
        verifyNoInteractions(notifications);
    }

    @Test void firstLikeIsInsertedAndTheResponseIsReloaded() {
        when(groups.likePost(11, 7)).thenReturn(1);
        assertEquals(11L, posts.like(auth, 3, 11).get("id"));
        verify(groups, never()).unlikePost(anyLong(), anyLong());
        verify(groups, times(2)).posts(3, 7L, 1000);
    }

    @Test void existingLikeIsRemoved() {
        posts.like(auth, 3, 11);
        verify(groups).unlikePost(11, 7);
    }

    @Test void favoriteTogglesAndReloadsTheOriginalResponseShape() {
        when(groups.favoritePost(11, 7)).thenReturn(1, 0);
        assertTrue(posts.favorite(auth, 3, 11).containsKey("replies"));
        verify(groups, never()).unfavoritePost(anyLong(), anyLong());
        posts.favorite(auth, 3, 11);
        verify(groups).unfavoritePost(11, 7);
        verify(groups, times(4)).posts(3, 7L, 1000);
    }

    @Test void onlyManagersCanModerateAndOnlySuppliedFieldsAreWritten() {
        assertStatus(403, () -> posts.moderatePost(auth, 3, 11, Map.of("isTop", true)));
        verify(groups, never()).setPostTop(anyBoolean(), anyLong(), anyLong());
        when(groups.membership(3, 7)).thenReturn(List.of(Map.of("role", "admin")));
        var input = Map.<String, Object>of("isTop", true, "isFeatured", false);
        posts.moderatePost(auth, 3, 11, input);
        verify(groups).setPostTop(true, 11, 3);
        verify(groups).setPostFeatured(false, 11, 3);
        verify(groups, never()).setPostStatus(anyString(), anyLong(), anyLong());
        verify(groups).log(3, 7, "moderate", "post", 11, input.toString());
    }

    @Test void moderationRetainsBlankStatusFallback() {
        when(groups.membership(3, 7)).thenReturn(List.of(Map.of("role", "owner")));
        posts.moderatePost(auth, 3, 11, Map.of("status", " "));
        verify(groups).setPostStatus("published", 11, 3);
        verify(groups, never()).setPostTop(anyBoolean(), anyLong(), anyLong());
        verify(groups, never()).setPostFeatured(anyBoolean(), anyLong(), anyLong());
    }

    @Test void groupDetailKeepsTheTwentyPostSummaryAndOtherFields() {
        when(groups.posts(3, 7L, 20)).thenReturn(List.of(post));
        var service = new GroupService(accounts, access, posts, groups);
        var detail = service.detail(3, auth);
        assertEquals(List.of(post), detail.get("posts"));
        assertEquals(java.util.Set.of("group", "posts", "announcements", "members", "files", "checkins", "membership", "pendingApplication"), detail.keySet());
        verify(groups).posts(3, 7L, 20);
    }

    private void assertStatus(int status, org.junit.jupiter.api.function.Executable action) {
        assertEquals(status, assertThrows(ResponseStatusException.class, action).getStatusCode().value());
    }
}
