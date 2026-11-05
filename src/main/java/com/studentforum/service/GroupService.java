package com.studentforum.service;

import com.studentforum.repository.GroupRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GroupService {
    private final GroupRepository groups;
    private final AccountAccessService accounts;
    private final NotificationService notifications;
    private final Path directory;

    public GroupService(AccountAccessService accounts, NotificationService notifications, GroupRepository groups, @Value("${forum.uploads}") String uploadPath) {
        this.groups = groups;
        this.accounts = accounts;
        this.notifications = notifications;
        this.directory = Path.of(uploadPath).toAbsolutePath().normalize();
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
        Map<String, Object> group = group(groupId);
        requireView(group, auth);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("group", group);
        result.put("posts", posts(groupId, auth, 20));
        result.put("announcements", groups.announcements(groupId));
        result.put("members", members(groupId));
        result.put("files", groups.files(groupId));
        result.put("checkins", checkins(groupId, auth));
        Long userId = userOrNull(auth);
        result.put("membership", userId == null ? null : groups.membership(groupId, userId).stream().findFirst().orElse(null));
        result.put("pendingApplication", userId == null ? null : groups.pendingApplication(groupId, userId).stream().findFirst().orElse(null));
        return result;
    }

    public Map<String, Object> update(Authentication auth, long groupId, Map<String, Object> input) {
        long user = accounts.id(auth);
        requireManager(groupId, user);
        Map<String, Object> old = group(groupId);
        String name = input.containsKey("name") ? text(input, "name") : String.valueOf(old.get("name"));
        String description = input.containsKey("description") ? text(input, "description") : String.valueOf(old.get("description"));
        String visibility = input.containsKey("visibility") ? value(input, "visibility", "") : String.valueOf(old.get("visibility"));
        String joinMode = input.containsKey("joinMode") ? value(input, "joinMode", "") : String.valueOf(old.get("join_mode"));
        if (name.length() < 2 || name.length() > 80 || description.length() > 2000 || !Set.of("public", "private").contains(visibility) || !Set.of("free", "apply", "invite").contains(joinMode))
            bad("小组设置无效");
        groups.updateGroup(name, description, input.getOrDefault("avatarUrl", old.get("avatar_url")), input.getOrDefault("tags", old.get("tags")), input.getOrDefault("subject", old.get("subject")), input.getOrDefault("school", old.get("school")), visibility, joinMode, groupId);
        log(groupId, user, "update", "group", groupId, name);
        return group(groupId);
    }

    public void dissolve(Authentication auth, long groupId) {
        long user = accounts.id(auth);
        Map<String, Object> g = group(groupId);
        if (rowNumber(g, "owner_id") != user) forbidden("仅组长可以解散小组");
        groups.disableGroup(groupId);
        log(groupId, user, "dissolve", "group", groupId, "组长解散小组");
    }

    public Map<String, Object> join(Authentication auth, long groupId) {
        long user = accounts.id(auth);
        Map<String, Object> g = group(groupId);
        if (!"active".equals(g.get("status"))) bad("小组不可加入");
        if (member(groupId, user) != null) return member(groupId, user);
        if (!"public".equals(g.get("visibility")) || !"free".equals(g.get("join_mode")))
            forbidden("该小组需要申请或邀请");
        addMember(groupId, user, "member");
        log(groupId, user, "join", "member", user, "自由加入");
        return member(groupId, user);
    }

    public Map<String, Object> apply(Authentication auth, long groupId, Map<String, Object> input) {
        long user = accounts.id(auth);
        Map<String, Object> g = group(groupId);
        if (member(groupId, user) != null) bad("你已经是小组成员");
        if ("invite".equals(g.get("join_mode"))) forbidden("该小组仅接受邀请");
        if (!groups.pendingRequest(groupId, user).isEmpty())
            bad("申请已提交");
        String message = text(input, "message");
        // 退出或被拒后重新申请：复用同一行并清掉历史记录，避免 (group_id,user_id,status) 唯一键冲突
        List<Map<String, Object>> previous = groups.latestRequest(groupId, user);
        if (previous.isEmpty()) {
            groups.createRequest(groupId, user, message);
        } else {
            long requestId = rowNumber(previous.getFirst(), "id");
            groups.removeOlderRequests(groupId, user, requestId);
            groups.reopenRequest(message, requestId);
        }
        notifyManagers(groupId, "有新的小组加入申请，请及时审核");
        log(groupId, user, "apply", "member", user, message);
        return Map.of("status", "pending");
    }

    public void invite(Authentication auth, long groupId, Map<String, Object> input) {
        long user = accounts.id(auth);
        requireManager(groupId, user);
        long invitee = number(input, "userId");
        if (member(groupId, invitee) != null) bad("对方已经是成员");
        addMember(groupId, invitee, "member");
        groups.notifyUser(invitee, "group", "你已被邀请加入小组：" + group(groupId).get("name"));
        log(groupId, user, "invite", "member", invitee, "邀请成员");
    }

    public void leave(Authentication auth, long groupId) {
        long user = accounts.id(auth);
        Map<String, Object> m = member(groupId, user);
        if (m == null) bad("你不是小组成员");
        if ("owner".equals(m.get("role"))) bad("组长请先转让组长或解散小组");
        groups.removeMember(groupId, user);
        groups.decrementMemberCount(groupId);
        log(groupId, user, "leave", "member", user, "退出小组");
    }

    public List<Map<String, Object>> memberList(long groupId, Authentication auth) {
        Map<String, Object> g = group(groupId);
        requireView(g, auth);
        return members(groupId);
    }

    public void memberRole(Authentication auth, long groupId, long userId, Map<String, Object> input) {
        long actor = accounts.id(auth);
        Map<String, Object> group = group(groupId);
        Map<String, Object> actorMember = requireMember(groupId, actor);
        String role = value(input, "role", "");
        if (!Set.of("owner", "admin", "member").contains(role)) bad("成员角色无效");
        if ("owner".equals(role) && !"owner".equals(actorMember.get("role"))) forbidden("仅组长可以转让组长");
        if (!"owner".equals(role) && rowNumber(group, "owner_id") == userId && "owner".equals(actorMember.get("role")))
            bad("请使用转让组长操作");
        if ("owner".equals(role)) {
            groups.demoteOwner(groupId);
            groups.transferOwner(userId, groupId);
        }
        groups.setMemberRole(role, groupId, userId);
        log(groupId, actor, "role", "member", userId, "设置角色为" + role);
    }

    public void removeMember(Authentication auth, long groupId, long userId) {
        long actor = accounts.id(auth);
        requireManager(groupId, actor);
        Map<String, Object> target = requireMember(groupId, userId);
        if ("owner".equals(target.get("role"))) bad("不能移除组长");
        groups.removeMember(groupId, userId);
        groups.decrementMemberCount(groupId);
        log(groupId, actor, "remove", "member", userId, "移除成员");
    }

    public List<Map<String, Object>> groupPosts(long groupId, Authentication auth, String keyword) {
        Map<String, Object> g = group(groupId);
        requireView(g, auth);
        List<Map<String, Object>> list = posts(groupId, auth, 100);
        if (keyword == null || keyword.isBlank()) return list;
        String q = keyword.toLowerCase();
        return list.stream().filter(p -> String.valueOf(p.get("title")).toLowerCase().contains(q) || String.valueOf(p.get("content")).toLowerCase().contains(q) || String.valueOf(p.get("tags")).toLowerCase().contains(q)).toList();
    }

    public Map<String, Object> createPost(Authentication auth, long groupId, Map<String, Object> input) {
        long user = accounts.id(auth);
        requireMember(groupId, user);
        String title = text(input, "title"), content = text(input, "content");
        if (title.length() < 2 || title.length() > 160 || content.isBlank()) bad("帖子标题或正文无效");
        long id = groups.insertPost(groupId, user, title, content, input.get("imagePath"), text(input, "tags"));
        groups.recordPostActivity(groupId);
        log(groupId, user, "post", "post", id, title);
        return post(groupId, id, auth);
    }

    public Map<String, Object> postDetail(long groupId, long postId, Authentication auth) {
        return post(groupId, postId, auth);
    }

    public void reply(Authentication auth, long groupId, long postId, Map<String, Object> input) {
        long user = accounts.id(auth);
        requireMember(groupId, user);
        post(groupId, postId, auth);
        String content = text(input, "content");
        if (content.isBlank() || content.length() > 3000) bad("回复内容无效");
        groups.insertReply(postId, user, content);
        groups.recordReplyActivity(groupId);
        notifications.mentions(user, content, "group_post", postId, null);
    }

    public Map<String, Object> like(Authentication auth, long groupId, long postId) {
        long user = accounts.id(auth);
        requireMember(groupId, user);
        post(groupId, postId, auth);
        int changed = groups.likePost(postId, user);
        if (changed == 0) groups.unlikePost(postId, user);
        return post(groupId, postId, auth);
    }

    public Map<String, Object> favorite(Authentication auth, long groupId, long postId) {
        long user = accounts.id(auth);
        requireMember(groupId, user);
        post(groupId, postId, auth);
        int changed = groups.favoritePost(postId, user);
        if (changed == 0)
            groups.unfavoritePost(postId, user);
        return post(groupId, postId, auth);
    }

    public void moderatePost(Authentication auth, long groupId, long postId, Map<String, Object> input) {
        long actor = accounts.id(auth);
        requireManager(groupId, actor);
        post(groupId, postId, auth);
        if (input.containsKey("status"))
            groups.setPostStatus(value(input, "status", "published"), postId, groupId);
        if (input.containsKey("isTop"))
            groups.setPostTop(Boolean.TRUE.equals(input.get("isTop")), postId, groupId);
        if (input.containsKey("isFeatured"))
            groups.setPostFeatured(Boolean.TRUE.equals(input.get("isFeatured")), postId, groupId);
        log(groupId, actor, "moderate", "post", postId, input.toString());
    }

    public List<Map<String, Object>> announcements(long groupId, Authentication auth) {
        requireView(group(groupId), auth);
        return groups.announcements(groupId);
    }

    public void announcement(Authentication auth, long groupId, Map<String, Object> input) {
        long user = accounts.id(auth);
        requireManager(groupId, user);
        String title = text(input, "title"), content = text(input, "content");
        if (title.isBlank() || content.isBlank()) bad("公告标题和内容不能为空");
        groups.insertAnnouncement(groupId, user, title, content);
        notifyMembers(groupId, "小组发布了新公告：" + title);
        log(groupId, user, "announcement", "announcement", 0, title);
    }

    public List<Map<String, Object>> files(long groupId, Authentication auth) {
        requireView(group(groupId), auth);
        return groups.files(groupId);
    }

    public void file(Authentication auth, long groupId, Map<String, Object> input) {
        long user = accounts.id(auth);
        requireMember(groupId, user);
        String name = text(input, "name"), path = text(input, "path");
        if (name.isBlank() || path.isBlank()) bad("文件名和路径不能为空");
        groups.insertFile(groupId, user, name, path, numberOr(input, "sizeBytes", 0), !Boolean.FALSE.equals(input.get("allowDownload")));
        log(groupId, user, "file", "file", 0, name);
    }

    public Map<String, Object> uploadFile(Authentication auth, long groupId, MultipartFile file) throws IOException {
        long user = accounts.id(auth);
        requireMember(groupId, user);
        accounts.writable(auth);
        if (file == null || file.isEmpty()) bad("请选择要上传的文件");
        if (file.getSize() > 25_000_000) bad("资料不能超过 25MB");
        String original = originalName(file);
        String extension = extensionOf(original);
        if (extension == null) bad("仅支持 PDF、Word、Excel、PPT、TXT、Markdown、压缩包和图片文件");
        Path folder = directory.resolve("groups").resolve(String.valueOf(groupId));
        Files.createDirectories(folder);
        String stored = UUID.randomUUID() + "." + extension;
        file.transferTo(folder.resolve(stored));
        return Map.of("path", "/uploads/groups/" + groupId + "/" + stored, "name", original, "sizeBytes", file.getSize());
    }

    public void deleteFile(Authentication auth, long groupId, long fileId) {
        long user = accounts.id(auth);
        requireMember(groupId, user);
        List<Map<String, Object>> rows = groups.findFile(fileId, groupId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资料不存在");
        Map<String, Object> row = rows.getFirst();
        if (rowNumber(row, "uploader_id") != user) requireManager(groupId, user);
        groups.deleteFile(fileId);
        removeStoredFile(String.valueOf(row.get("path")));
        log(groupId, user, "file_delete", "file", fileId, String.valueOf(row.get("name")));
    }

    public List<Map<String, Object>> checkins(long groupId, Authentication auth) {
        requireView(group(groupId), auth);
        Long user = userOrNull(auth);
        return groups.checkins(groupId, user);
    }

    public void createCheckin(Authentication auth, long groupId, Map<String, Object> input) {
        long user = accounts.id(auth);
        requireManager(groupId, user);
        String title = text(input, "title");
        if (title.isBlank()) bad("打卡任务标题不能为空");
        groups.insertCheckin(groupId, user, title, text(input, "description"), input.get("dueAt"));
        log(groupId, user, "checkin_create", "checkin", 0, title);
    }

    public void completeCheckin(Authentication auth, long groupId, long checkinId, Map<String, Object> input) {
        long user = accounts.id(auth);
        requireMember(groupId, user);
        groups.recordCheckin(checkinId, user, text(input, "content"));
        groups.recordCheckinActivity(groupId);
    }

    public void report(Authentication auth, long groupId, Map<String, Object> input) {
        long user = accounts.id(auth);
        requireMember(groupId, user);
        groups.insertReport(groupId, user, value(input, "targetType", "group"), numberOr(input, "targetId", groupId), text(input, "reason"), text(input, "description"));
        notifyManagers(groupId, "小组收到新的违规内容举报");
    }

    public List<Map<String, Object>> reports(Authentication auth, long groupId) {
        long user = accounts.id(auth);
        requireManager(groupId, user);
        return groups.reports(groupId);
    }

    public void handleReport(Authentication auth, long groupId, long reportId, Map<String, Object> input) {
        long user = accounts.id(auth);
        requireManager(groupId, user);
        String status = value(input, "status", "");
        if (!Set.of("resolved", "rejected").contains(status)) bad("举报状态无效");
        groups.reviewReport(status, user, reportId, groupId);
        log(groupId, user, "report_" + status, "report", reportId, text(input, "note"));
    }

    public List<Map<String, Object>> requests(Authentication auth, long groupId) {
        long user = accounts.id(auth);
        requireManager(groupId, user);
        return groups.joinRequests(groupId);
    }

    public void reviewRequest(Authentication auth, long groupId, long requestId, Map<String, Object> input) {
        long actor = accounts.id(auth);
        requireManager(groupId, actor);
        String status = value(input, "status", "");
        if (!Set.of("approved", "rejected").contains(status)) bad("审核状态无效");
        List<Map<String, Object>> rows = groups.reviewableRequest(requestId, groupId);
        if (rows.isEmpty()) bad("申请不存在或已处理");
        long user = rowNumber(rows.getFirst(), "user_id");
        // 同一位用户在同一小组只保留一条申请记录，防止历史 approved/rejected 记录撞唯一键
        groups.removeOlderRequests(groupId, user, requestId);
        groups.reviewRequest(status, actor, requestId);
        if ("approved".equals(status) && member(groupId, user) == null) addMember(groupId, user, "member");
        groups.notifyUser(user, "group", "小组加入申请已" + (("approved".equals(status)) ? "通过" : "拒绝"));
        log(groupId, actor, "review", "join_request", requestId, status);
    }

    public List<Map<String, Object>> adminGroups(Authentication auth, String keyword) {
        accounts.admin(auth);
        String q = keyword.trim();
        return groups.adminGroups(q);
    }

    public void adminUpdate(Authentication auth, long groupId, Map<String, Object> input) {
        accounts.admin(auth);
        group(groupId);
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

    private List<Map<String, Object>> posts(long groupId, Authentication auth, int limit) {
        Long user = userOrNull(auth);
        return groups.posts(groupId, user, limit);
    }

    private Map<String, Object> post(long groupId, long postId, Authentication auth) {
        requireView(group(groupId), auth);
        List<Map<String, Object>> rows = posts(groupId, auth, 1000).stream().filter(p -> rowNumber(p, "id") == postId).toList();
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "帖子不存在");
        Map<String, Object> result = new LinkedHashMap<>(rows.getFirst());
        result.put("replies", groups.replies(postId));
        return result;
    }

    private Map<String, Object> group(long id) {
        List<Map<String, Object>> rows = groups.findGroup(id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "小组不存在");
        return rows.getFirst();
    }

    private List<Map<String, Object>> members(long groupId) {
        return groups.members(groupId);
    }

    private Map<String, Object> member(long groupId, long userId) {
        return groups.membership(groupId, userId).stream().findFirst().orElse(null);
    }

    private Map<String, Object> requireMember(long groupId, long userId) {
        Map<String, Object> m = member(groupId, userId);
        if (m == null) forbidden("请先加入小组");
        return m;
    }

    private void requireView(Map<String, Object> group, Authentication auth) {
        if ("private".equals(group.get("visibility"))) {
            Long user = userOrNull(auth);
            if (user == null || member(rowNumber(group, "id"), user) == null) forbidden("这是私密小组，请先加入后查看");
        }
    }

    private void requireManager(long groupId, long userId) {
        Map<String, Object> m = requireMember(groupId, userId);
        if (!Set.of("owner", "admin").contains(m.get("role"))) forbidden("仅组长或管理员可以操作");
    }

    private void addMember(long groupId, long userId, String role) {
        groups.addMember(groupId, userId, role);
        groups.refreshMemberCount(groupId);
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

    private Long userOrNull(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) return null;
        return Long.parseLong(auth.getName());
    }

    private String text(Map<String, Object> input, String key) {
        return input == null || input.get(key) == null ? "" : String.valueOf(input.get(key)).trim();
    }

    private String value(Map<String, Object> input, String key, String fallback) {
        String v = text(input, key);
        return v.isBlank() ? fallback : v;
    }

    private long number(Map<String, Object> input, String key) {
        Object v = input.get(key);
        if (v == null) bad("缺少" + key);
        return Long.parseLong(String.valueOf(v));
    }

    private long rowNumber(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private long numberOr(Map<String, Object> input, String key, long fallback) {
        Object v = input.get(key);
        return v == null ? fallback : Long.parseLong(String.valueOf(v));
    }

    private String originalName(MultipartFile file) {
        String name = String.valueOf(file.getOriginalFilename());
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = (slash < 0 ? name : name.substring(slash + 1)).trim();
        if (name.isEmpty() || name.length() > 160) name = "小组资料";
        return name;
    }

    private String extensionOf(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0 || dot == lower.length() - 1) return null;
        String extension = lower.substring(dot + 1);
        if ("jpeg".equals(extension)) return "jpg";
        return Set.of("pdf", "txt", "md", "csv", "zip", "rar", "7z", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "jpg", "png", "webp", "gif").contains(extension) ? extension : null;
    }

    private void removeStoredFile(String path) {
        if (path == null || !path.matches("/uploads/(groups/[0-9]+/)?[0-9a-fA-F-]{36}\\.[A-Za-z0-9]{1,5}")) return;
        try {
            Files.deleteIfExists(directory.resolve(path.substring("/uploads/".length())));
        } catch (IOException ignored) {
        }
    }

    private void bad(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private void forbidden(String message) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
}
