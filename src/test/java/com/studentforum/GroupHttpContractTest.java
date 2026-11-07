package com.studentforum;

import com.studentforum.controller.GroupController;
import com.studentforum.service.GroupService;
import com.studentforum.service.GroupPostService;
import com.studentforum.service.GroupFileService;
import com.studentforum.service.GroupMembershipService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.*;

class GroupHttpContractTest {
    private final GroupService groups = mock(GroupService.class);
    private final GroupPostService posts = mock(GroupPostService.class);
    private final GroupFileService files = mock(GroupFileService.class);
    private final GroupMembershipService membership = mock(GroupMembershipService.class);

    @Test void rankingRouteKeepsTheJsonListAndQueryParameter() throws Exception {
        GroupService groups = mock(GroupService.class);
        when(groups.rankings("members")).thenReturn(List.of(Map.of("id", 4L)));
        MockMvcBuilders.standaloneSetup(new GroupController(groups, posts, files, membership)).build()
                .perform(get("/api/groups/rankings").param("type", "members"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(4));
        verify(groups).rankings("members");
    }

    @Test void postListAndDetailKeepQueryAndResponseFields() throws Exception {
        when(posts.groupPosts(3, null, "math")).thenReturn(List.of(Map.of("id", 11, "liked", false)));
        when(posts.postDetail(3, 11, null)).thenReturn(Map.of("id", 11, "replies", List.of(Map.of("content", "reply"))));
        var mvc = MockMvcBuilders.standaloneSetup(new GroupController(groups, posts, files, membership)).build();
        mvc.perform(get("/api/groups/3/posts").param("keyword", "math"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(11)).andExpect(jsonPath("$[0].liked").value(false));
        mvc.perform(get("/api/groups/3/posts/11"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replies[0].content").value("reply"));
        verify(posts).groupPosts(3, null, "math");
        verify(posts).postDetail(3, 11, null);
        verifyNoInteractions(groups);
    }

    @Test void postCreationAndReplyKeepTheirBodiesAndEmptyReplyResponse() throws Exception {
        var input = Map.<String, Object>of("title", "Study", "content", "body");
        when(posts.createPost(null, 3, input)).thenReturn(Map.of("id", 11, "replies", List.of()));
        var mvc = MockMvcBuilders.standaloneSetup(new GroupController(groups, posts, files, membership)).build();
        mvc.perform(post("/api/groups/3/posts").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Study\",\"content\":\"body\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(11)).andExpect(jsonPath("$.replies").isArray());
        mvc.perform(post("/api/groups/3/posts/11/replies").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"reply\"}"))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(""));
        verify(posts).createPost(null, 3, input);
        verify(posts).reply(null, 3, 11, Map.of("content", "reply"));
        verifyNoInteractions(groups);
    }

    @Test void interactionAndModerationRoutesDirectlyUseThePostService() throws Exception {
        when(posts.like(null, 3, 11)).thenReturn(Map.of("id", 11, "liked", true));
        when(posts.favorite(null, 3, 11)).thenReturn(Map.of("id", 11, "favorited", false));
        var mvc = MockMvcBuilders.standaloneSetup(new GroupController(groups, posts, files, membership)).build();
        mvc.perform(post("/api/groups/3/posts/11/like"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.liked").value(true));
        mvc.perform(post("/api/groups/3/posts/11/favorite"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.favorited").value(false));
        mvc.perform(patch("/api/groups/3/posts/11").contentType(MediaType.APPLICATION_JSON).content("{\"isTop\":true}"))
                .andExpect(status().isOk());
        verify(posts).like(null, 3, 11);
        verify(posts).favorite(null, 3, 11);
        verify(posts).moderatePost(null, 3, 11, Map.of("isTop", true));
        verifyNoInteractions(groups);
    }

    @Test void fileListAndRegistrationKeepTheExistingFieldNamesAndBody() throws Exception {
        when(files.files(3, null)).thenReturn(List.of(Map.of("id", 12, "size_bytes", 3, "allow_download", false)));
        var mvc = MockMvcBuilders.standaloneSetup(new GroupController(groups, posts, files, membership)).build();
        mvc.perform(get("/api/groups/3/files"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].size_bytes").value(3)).andExpect(jsonPath("$[0].allow_download").value(false));
        mvc.perform(post("/api/groups/3/files").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"study.txt\",\"path\":\"/file\",\"allowDownload\":false}"))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(""));
        verify(files).files(3, null);
        verify(files).file(null, 3, Map.of("name", "study.txt", "path", "/file", "allowDownload", false));
        verifyNoInteractions(groups, posts);
    }

    @Test void fileUploadKeepsMultipartParameterAndUploadResponseShape() throws Exception {
        var file = new MockMultipartFile("file", "study.txt", "text/plain", new byte[]{1, 2, 3});
        when(files.uploadFile(null, 3, file)).thenReturn(Map.of("path", "/uploads/groups/3/file.txt", "name", "study.txt", "sizeBytes", 3));
        var mvc = MockMvcBuilders.standaloneSetup(new GroupController(groups, posts, files, membership)).build();
        mvc.perform(multipart("/api/groups/3/files/upload").file(file))
                .andExpect(status().isOk()).andExpect(jsonPath("$.path").value("/uploads/groups/3/file.txt"))
                .andExpect(jsonPath("$.name").value("study.txt")).andExpect(jsonPath("$.sizeBytes").value(3));
        verify(files).uploadFile(null, 3, file);
        verifyNoInteractions(groups, posts);
    }

    @Test void fileDeletionKeepsTheScopedRouteAndEmptyResponse() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new GroupController(groups, posts, files, membership)).build();
        mvc.perform(delete("/api/groups/3/files/12"))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(""));
        verify(files).deleteFile(null, 3, 12);
        verifyNoInteractions(groups, posts);
    }
    @Test void joinAndApplicationKeepResponseShapesAndOptionalBodies() throws Exception {
        when(membership.join(null, 3)).thenReturn(Map.of("group_id", 3, "user_id", 7, "role", "member"));
        when(membership.apply(null, 3, null)).thenReturn(Map.of("status", "pending"));
        when(membership.apply(null, 3, Map.of("message", "join"))).thenReturn(Map.of("status", "pending"));
        var mvc = MockMvcBuilders.standaloneSetup(new GroupController(groups, posts, files, membership)).build();
        mvc.perform(post("/api/groups/3/join"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.group_id").value(3)).andExpect(jsonPath("$.role").value("member"));
        mvc.perform(post("/api/groups/3/apply"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("pending"));
        mvc.perform(post("/api/groups/3/apply").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"join\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("pending"));
        verify(membership).join(null, 3);
        verify(membership).apply(null, 3, null);
        verify(membership).apply(null, 3, Map.of("message", "join"));
        verifyNoInteractions(groups, posts, files);
    }

    @Test void invitationAndLeaveKeepTheirBodiesAndEmptyResponses() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new GroupController(groups, posts, files, membership)).build();
        mvc.perform(post("/api/groups/3/invite").contentType(MediaType.APPLICATION_JSON).content("{\"userId\":8}"))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(""));
        mvc.perform(post("/api/groups/3/leave"))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(""));
        verify(membership).invite(null, 3, Map.of("userId", 8));
        verify(membership).leave(null, 3);
        verifyNoInteractions(groups, posts, files);
    }

    @Test void memberListRoleAndRemovalRoutesDirectlyUseMembershipService() throws Exception {
        when(membership.memberList(3, null)).thenReturn(List.of(Map.of("user_id", 8, "role", "member")));
        var mvc = MockMvcBuilders.standaloneSetup(new GroupController(groups, posts, files, membership)).build();
        mvc.perform(get("/api/groups/3/members"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].user_id").value(8)).andExpect(jsonPath("$[0].role").value("member"));
        mvc.perform(patch("/api/groups/3/members/8").contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"admin\"}"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/groups/3/members/8"))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(""));
        verify(membership).memberList(3, null);
        verify(membership).memberRole(null, 3, 8, Map.of("role", "admin"));
        verify(membership).removeMember(null, 3, 8);
        verifyNoInteractions(groups, posts, files);
    }

    @Test void applicationListAndReviewKeepTheirScopedRoutesAndStatusField() throws Exception {
        when(membership.requests(null, 3)).thenReturn(List.of(Map.of("id", 42, "status", "pending")));
        var mvc = MockMvcBuilders.standaloneSetup(new GroupController(groups, posts, files, membership)).build();
        mvc.perform(get("/api/groups/3/join-requests"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(42)).andExpect(jsonPath("$[0].status").value("pending"));
        mvc.perform(patch("/api/groups/3/join-requests/42").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"approved\"}"))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(""));
        verify(membership).requests(null, 3);
        verify(membership).reviewRequest(null, 3, 42, Map.of("status", "approved"));
        verifyNoInteractions(groups, posts, files);
    }
}
