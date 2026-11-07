package com.studentforum.service;

import com.studentforum.repository.GroupRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GroupPostService {
    private final GroupRepository groups;
    private final AccountAccessService accounts;
    private final GroupAccessService access;
    private final NotificationService notifications;

    public GroupPostService(GroupRepository groups, AccountAccessService accounts,
                            GroupAccessService access, NotificationService notifications) {
        this.groups = groups;
        this.accounts = accounts;
        this.access = access;
        this.notifications = notifications;
    }

    public List<Map<String, Object>> groupPosts(long groupId, Authentication auth, String keyword) {
        Map<String, Object> g = access.group(groupId);
        access.requireView(g, auth);
        List<Map<String, Object>> list = posts(groupId, auth, 100);
        if (keyword == null || keyword.isBlank()) return list;
        String q = keyword.toLowerCase();
        return list.stream().filter(p -> String.valueOf(p.get("title")).toLowerCase().contains(q) || String.valueOf(p.get("content")).toLowerCase().contains(q) || String.valueOf(p.get("tags")).toLowerCase().contains(q)).toList();
    }

    public Map<String, Object> createPost(Authentication auth, long groupId, Map<String, Object> input) {
        long user = accounts.id(auth);
        access.requireMember(groupId, user);
        String title = text(input, "title"), content = text(input, "content");
        if (title.length() < 2 || title.length() > 160 || content.isBlank()) bad("帖子标题或正文无效");
        long id = groups.insertPost(groupId, user, title, content, input.get("imagePath"), text(input, "tags"));
        groups.recordPostActivity(groupId);
        log(groupId, user, "post", "post", id, title);
        return postDetail(groupId, id, auth);
    }

    public void reply(Authentication auth, long groupId, long postId, Map<String, Object> input) {
        long user = accounts.id(auth);
        access.requireMember(groupId, user);
        postDetail(groupId, postId, auth);
        String content = text(input, "content");
        if (content.isBlank() || content.length() > 3000) bad("回复内容无效");
        groups.insertReply(postId, user, content);
        groups.recordReplyActivity(groupId);
        notifications.mentions(user, content, "group_post", postId, null);
    }

    public Map<String, Object> like(Authentication auth, long groupId, long postId) {
        long user = accounts.id(auth);
        access.requireMember(groupId, user);
        postDetail(groupId, postId, auth);
        int changed = groups.likePost(postId, user);
        if (changed == 0) groups.unlikePost(postId, user);
        return postDetail(groupId, postId, auth);
    }

    public Map<String, Object> favorite(Authentication auth, long groupId, long postId) {
        long user = accounts.id(auth);
        access.requireMember(groupId, user);
        postDetail(groupId, postId, auth);
        int changed = groups.favoritePost(postId, user);
        if (changed == 0)
            groups.unfavoritePost(postId, user);
        return postDetail(groupId, postId, auth);
    }

    public void moderatePost(Authentication auth, long groupId, long postId, Map<String, Object> input) {
        long actor = accounts.id(auth);
        access.requireManager(groupId, actor);
        postDetail(groupId, postId, auth);
        if (input.containsKey("status"))
            groups.setPostStatus(value(input, "status", "published"), postId, groupId);
        if (input.containsKey("isTop"))
            groups.setPostTop(Boolean.TRUE.equals(input.get("isTop")), postId, groupId);
        if (input.containsKey("isFeatured"))
            groups.setPostFeatured(Boolean.TRUE.equals(input.get("isFeatured")), postId, groupId);
        log(groupId, actor, "moderate", "post", postId, input.toString());
    }

    List<Map<String, Object>> posts(long groupId, Authentication auth, int limit) {
        Long user = access.userOrNull(auth);
        return groups.posts(groupId, user, limit);
    }

    public Map<String, Object> postDetail(long groupId, long postId, Authentication auth) {
        access.requireView(access.group(groupId), auth);
        List<Map<String, Object>> rows = posts(groupId, auth, 1000).stream().filter(p -> rowNumber(p, "id") == postId).toList();
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "帖子不存在");
        Map<String, Object> result = new LinkedHashMap<>(rows.getFirst());
        result.put("replies", groups.replies(postId));
        return result;
    }

    private String text(Map<String, Object> input, String key) {
        return input == null || input.get(key) == null ? "" : String.valueOf(input.get(key)).trim();
    }

    private String value(Map<String, Object> input, String key, String fallback) {
        String v = text(input, key);
        return v.isBlank() ? fallback : v;
    }

    private long rowNumber(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private void log(long groupId, long actor, String action, String type, long target, String detail) {
        groups.log(groupId, actor, action, type, target, detail == null ? "" : detail);
    }

    private void bad(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
