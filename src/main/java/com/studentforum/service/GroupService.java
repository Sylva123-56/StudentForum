package com.studentforum.service;

import com.studentforum.repository.GroupRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GroupService {
    private final GroupRepository groups;
    private final AccountAccessService accounts;
    private final GroupAccessService access;
    private final GroupPostService posts;

    public GroupService(AccountAccessService accounts, GroupAccessService access, GroupPostService posts, GroupRepository groups) {
        this.groups = groups;
        this.accounts = accounts;
        this.access = access;
        this.posts = posts;
    }

    public List<Map<String, Object>> rankings(String type) {
        return groups.rankings(type);
    }

    public List<Map<String, Object>> recommended(Authentication auth) {
        long user = accounts.id(auth);
        Map<String, Object> profile = accounts.current(auth);
        String subject = String.valueOf(profile.getOrDefault("subject_preference", ""));
        return groups.recommended(user, subject);
    }

    public List<Map<String, Object>> list(String keyword, String tag, String subject,
                                          String school, String status, String sort) {
        return groups.search(keyword, tag, subject, school, status, sort);
    }

    public Map<String, Object> create(Authentication auth, Map<String, Object> input) {
        long user = accounts.id(auth);
        String name = text(input, "name"), description = text(input, "description");
        if (name.length() < 2 || name.length() > 80) bad("小组名称需要 2 至 80 个字");
        if (description.length() > 2000) bad("小组简介不能超过 2000 个字");
        String visibility = value(input, "visibility", "public"), joinMode = value(input, "joinMode", "free");
        if (!Set.of("public", "private").contains(visibility) || !Set.of("free", "apply", "invite").contains(joinMode))
            bad("小组可见性或加入方式无效");
        if ("private".equals(visibility) && "free".equals(joinMode)) joinMode = "apply";
        long groupId = groups.insertGroup(name, description, input.get("avatarUrl"), text(input, "tags"), text(input, "subject"), text(input, "school"), user, visibility, joinMode);
        groups.addOwner(groupId, user);
        log(groupId, user, "create", "group", groupId, name);
        return detail(groupId, auth);
    }

    public Map<String, Object> detail(long groupId, Authentication auth) {
        Map<String, Object> group = access.group(groupId);
        access.requireView(group, auth);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("group", group);
        result.put("posts", posts.posts(groupId, auth, 20));
        result.put("announcements", groups.announcements(groupId));
        result.put("members", members(groupId));
        result.put("files", groups.files(groupId));
        result.put("checkins", checkins(groupId, auth));
        Long userId = access.userOrNull(auth);
        result.put("membership", userId == null ? null : groups.membership(groupId, userId).stream().findFirst().orElse(null));
        result.put("pendingApplication", userId == null ? null : groups.pendingApplication(groupId, userId).stream().findFirst().orElse(null));
        return result;
    }

    public Map<String, Object> update(Authentication auth, long groupId, Map<String, Object> input) {
        long user = accounts.id(auth);
        access.requireManager(groupId, user);
        Map<String, Object> old = access.group(groupId);
        String name = input.containsKey("name") ? text(input, "name") : String.valueOf(old.get("name"));
        String description = input.containsKey("description") ? text(input, "description") : String.valueOf(old.get("description"));
        String visibility = input.containsKey("visibility") ? value(input, "visibility", "") : String.valueOf(old.get("visibility"));
        String joinMode = input.containsKey("joinMode") ? value(input, "joinMode", "") : String.valueOf(old.get("join_mode"));
        if (name.length() < 2 || name.length() > 80 || description.length() > 2000 || !Set.of("public", "private").contains(visibility) || !Set.of("free", "apply", "invite").contains(joinMode))
            bad("小组设置无效");
        groups.updateGroup(name, description, input.getOrDefault("avatarUrl", old.get("avatar_url")), input.getOrDefault("tags", old.get("tags")), input.getOrDefault("subject", old.get("subject")), input.getOrDefault("school", old.get("school")), visibility, joinMode, groupId);
        log(groupId, user, "update", "group", groupId, name);
        return access.group(groupId);
    }

    public void dissolve(Authentication auth, long groupId) {
        long user = accounts.id(auth);
        Map<String, Object> g = access.group(groupId);
        if (rowNumber(g, "owner_id") != user) forbidden("仅组长可以解散小组");
        groups.disableGroup(groupId);
        log(groupId, user, "dissolve", "group", groupId, "组长解散小组");
    }

    public List<Map<String, Object>> announcements(long groupId, Authentication auth) {
        access.requireView(access.group(groupId), auth);
        return groups.announcements(groupId);
    }

    public void announcement(Authentication auth, long groupId, Map<String, Object> input) {
        long user = accounts.id(auth);
        access.requireManager(groupId, user);
        String title = text(input, "title"), content = text(input, "content");
        if (title.isBlank() || content.isBlank()) bad("公告标题和内容不能为空");
        groups.insertAnnouncement(groupId, user, title, content);
        notifyMembers(groupId, "小组发布了新公告：" + title);
        log(groupId, user, "announcement", "announcement", 0, title);
    }

    public List<Map<String, Object>> checkins(long groupId, Authentication auth) {
        access.requireView(access.group(groupId), auth);
        Long user = access.userOrNull(auth);
        return groups.checkins(groupId, user);
    }

    public void createCheckin(Authentication auth, long groupId, Map<String, Object> input) {
        long user = accounts.id(auth);
        access.requireManager(groupId, user);
        String title = text(input, "title");
        if (title.isBlank()) bad("打卡任务标题不能为空");
        groups.insertCheckin(groupId, user, title, text(input, "description"), input.get("dueAt"));
        log(groupId, user, "checkin_create", "checkin", 0, title);
    }

    public void completeCheckin(Authentication auth, long groupId, long checkinId, Map<String, Object> input) {
        long user = accounts.id(auth);
        access.requireMember(groupId, user);
        groups.recordCheckin(checkinId, user, text(input, "content"));
        groups.recordCheckinActivity(groupId);
    }

    public void report(Authentication auth, long groupId, Map<String, Object> input) {
        long user = accounts.id(auth);
        access.requireMember(groupId, user);
        groups.insertReport(groupId, user, value(input, "targetType", "group"), numberOr(input, "targetId", groupId), text(input, "reason"), text(input, "description"));
        notifyManagers(groupId, "小组收到新的违规内容举报");
    }

    public List<Map<String, Object>> reports(Authentication auth, long groupId) {
        long user = accounts.id(auth);
        access.requireManager(groupId, user);
        return groups.reports(groupId);
    }

    public void handleReport(Authentication auth, long groupId, long reportId, Map<String, Object> input) {
        long user = accounts.id(auth);
        access.requireManager(groupId, user);
        String status = value(input, "status", "");
        if (!Set.of("resolved", "rejected").contains(status)) bad("举报状态无效");
        groups.reviewReport(status, user, reportId, groupId);
        log(groupId, user, "report_" + status, "report", reportId, text(input, "note"));
    }

    public List<Map<String, Object>> adminGroups(Authentication auth, String keyword) {
        accounts.admin(auth);
        String q = keyword.trim();
        return groups.adminGroups(q);
    }

    public void adminUpdate(Authentication auth, long groupId, Map<String, Object> input) {
        accounts.admin(auth);
        access.group(groupId);
        String status = value(input, "status", "");
        if (!Set.of("active", "disabled").contains(status)) bad("小组状态无效");
        groups.setGroupStatus(status, groupId);
        log(groupId, accounts.id(auth), "admin_status", "group", groupId, status);
    }

    public List<Map<String, Object>> logs(Authentication auth, Long groupId) {
        accounts.admin(auth);
        if (groupId == null)
            return groups.allLogs();
        return groups.groupLogs(groupId);
    }

    private List<Map<String, Object>> members(long groupId) {
        return groups.members(groupId);
    }

    private void notifyManagers(long groupId, String message) {
        groups.notifyManagers(message, groupId);
    }

    private void notifyMembers(long groupId, String message) {
        groups.notifyMembers(message, groupId);
    }

    private void log(long groupId, long actor, String action, String type, long target, String detail) {
        groups.log(groupId, actor, action, type, target, detail == null ? "" : detail);
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

    private long numberOr(Map<String, Object> input, String key, long fallback) {
        Object v = input.get(key);
        return v == null ? fallback : Long.parseLong(String.valueOf(v));
    }

    private void bad(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private void forbidden(String message) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
}
