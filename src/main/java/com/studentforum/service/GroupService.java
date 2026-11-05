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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GroupService {
    private final JdbcTemplate db;
    private final GroupRepository groups;
    private final AccountAccessService accounts;
    private final NotificationService notifications;
    private final Path directory;

    public GroupService(JdbcTemplate db, AccountAccessService accounts, NotificationService notifications, GroupRepository groups, @Value("${forum.uploads}") String uploadPath) {
        this.db = db;
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
        db.update("INSERT INTO study_groups(name,description,avatar_url,tags,subject,school,owner_id,visibility,join_mode,member_count) VALUES(?,?,?,?,?,?,?,?,?,1)", name, description, input.get("avatarUrl"), text(input, "tags"), text(input, "subject"), text(input, "school"), user, visibility, joinMode);
        long groupId = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        db.update("INSERT INTO study_group_members(group_id,user_id,role) VALUES(?,?, 'owner')", groupId, user);
        log(groupId, user, "create", "group", groupId, name);
        return detail(groupId, auth);
    }

    public Map<String, Object> detail(long groupId, Authentication auth) {
        Map<String, Object> group = group(groupId);
        requireView(group, auth);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("group", group);
        result.put("posts", posts(groupId, auth, 20));
        result.put("announcements", db.queryForList("SELECT a.*,u.username AS author_username FROM study_group_announcements a JOIN users u ON u.id=a.author_id WHERE a.group_id=? ORDER BY a.created_at DESC LIMIT 10", groupId));
        result.put("members", members(groupId));
        result.put("files", db.queryForList("SELECT f.*,u.username AS uploader_username FROM study_group_files f JOIN users u ON u.id=f.uploader_id WHERE f.group_id=? ORDER BY f.created_at DESC LIMIT 20", groupId));
        result.put("checkins", checkins(groupId, auth));
        Long userId = userOrNull(auth);
        result.put("membership", userId == null ? null : db.queryForList("SELECT * FROM study_group_members WHERE group_id=? AND user_id=?", groupId, userId).stream().findFirst().orElse(null));
        result.put("pendingApplication", userId == null ? null : db.queryForList("SELECT id,status,message FROM study_group_join_requests WHERE group_id=? AND user_id=? AND status='pending'", groupId, userId).stream().findFirst().orElse(null));
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
        db.update("UPDATE study_groups SET name=?,description=?,avatar_url=?,tags=?,subject=?,school=?,visibility=?,join_mode=? WHERE id=?", name, description, input.getOrDefault("avatarUrl", old.get("avatar_url")), input.getOrDefault("tags", old.get("tags")), input.getOrDefault("subject", old.get("subject")), input.getOrDefault("school", old.get("school")), visibility, joinMode, groupId);
        log(groupId, user, "update", "group", groupId, name);
        return group(groupId);
    }

    public void dissolve(Authentication auth, long groupId) {
        long user = accounts.id(auth);
        Map<String, Object> g = group(groupId);
        if (rowNumber(g, "owner_id") != user) forbidden("仅组长可以解散小组");
        db.update("UPDATE study_groups SET status='disabled' WHERE id=?", groupId);
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
        if (!db.queryForList("SELECT id FROM study_group_join_requests WHERE group_id=? AND user_id=? AND status='pending'", groupId, user).isEmpty())
            bad("申请已提交");
        String message = text(input, "message");
        // 退出或被拒后重新申请：复用同一行并清掉历史记录，避免 (group_id,user_id,status) 唯一键冲突
        List<Map<String, Object>> previous = db.queryForList("SELECT id FROM study_group_join_requests WHERE group_id=? AND user_id=? ORDER BY id DESC LIMIT 1", groupId, user);
        if (previous.isEmpty()) {
            db.update("INSERT INTO study_group_join_requests(group_id,user_id,message) VALUES(?,?,?)", groupId, user, message);
        } else {
            long requestId = rowNumber(previous.getFirst(), "id");
            db.update("DELETE FROM study_group_join_requests WHERE group_id=? AND user_id=? AND id<>?", groupId, user, requestId);
            db.update("UPDATE study_group_join_requests SET status='pending',message=?,reviewer_id=NULL,handled_at=NULL,created_at=NOW() WHERE id=?", message, requestId);
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
        db.update("INSERT INTO notifications(user_id,kind,message) VALUES(?,?,?)", invitee, "group", "你已被邀请加入小组：" + group(groupId).get("name"));
        log(groupId, user, "invite", "member", invitee, "邀请成员");
    }

    public void leave(Authentication auth, long groupId) {
        long user = accounts.id(auth);
        Map<String, Object> m = member(groupId, user);
        if (m == null) bad("你不是小组成员");
        if ("owner".equals(m.get("role"))) bad("组长请先转让组长或解散小组");
        db.update("DELETE FROM study_group_members WHERE group_id=? AND user_id=?", groupId, user);
        db.update("UPDATE study_groups SET member_count=GREATEST(0,member_count-1) WHERE id=?", groupId);
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
            db.update("UPDATE study_group_members SET role='admin' WHERE group_id=? AND role='owner'", groupId);
            db.update("UPDATE study_groups SET owner_id=? WHERE id=?", userId, groupId);
        }
        db.update("UPDATE study_group_members SET role=? WHERE group_id=? AND user_id=?", role, groupId, userId);
        log(groupId, actor, "role", "member", userId, "设置角色为" + role);
    }

    public void removeMember(Authentication auth, long groupId, long userId) {
        long actor = accounts.id(auth);
        requireManager(groupId, actor);
        Map<String, Object> target = requireMember(groupId, userId);
        if ("owner".equals(target.get("role"))) bad("不能移除组长");
        db.update("DELETE FROM study_group_members WHERE group_id=? AND user_id=?", groupId, userId);
        db.update("UPDATE study_groups SET member_count=GREATEST(0,member_count-1) WHERE id=?", groupId);
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
        db.update("INSERT INTO study_group_posts(group_id,author_id,title,content,image_path,tags) VALUES(?,?,?,?,?,?)", groupId, user, title, content, input.get("imagePath"), text(input, "tags"));
        long id = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        db.update("UPDATE study_groups SET post_count=post_count+1,activity_score=activity_score+3,updated_at=NOW() WHERE id=?", groupId);
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
        db.update("INSERT INTO study_group_post_replies(post_id,author_id,content) VALUES(?,?,?)", postId, user, content);
        db.update("UPDATE study_groups SET activity_score=activity_score+1 WHERE id=?", groupId);
        notifications.mentions(user, content, "group_post", postId, null);
    }

    public Map<String, Object> like(Authentication auth, long groupId, long postId) {
        long user = accounts.id(auth);
        requireMember(groupId, user);
        post(groupId, postId, auth);
        int changed = db.update("INSERT IGNORE INTO study_group_post_likes(post_id,user_id) VALUES(?,?)", postId, user);
        if (changed == 0) db.update("DELETE FROM study_group_post_likes WHERE post_id=? AND user_id=?", postId, user);
        return post(groupId, postId, auth);
    }

    public Map<String, Object> favorite(Authentication auth, long groupId, long postId) {
        long user = accounts.id(auth);
        requireMember(groupId, user);
        post(groupId, postId, auth);
        int changed = db.update("INSERT IGNORE INTO study_group_post_favorites(post_id,user_id) VALUES(?,?)", postId, user);
        if (changed == 0)
            db.update("DELETE FROM study_group_post_favorites WHERE post_id=? AND user_id=?", postId, user);
        return post(groupId, postId, auth);
    }

    public void moderatePost(Authentication auth, long groupId, long postId, Map<String, Object> input) {
        long actor = accounts.id(auth);
        requireManager(groupId, actor);
        post(groupId, postId, auth);
        if (input.containsKey("status"))
            db.update("UPDATE study_group_posts SET status=? WHERE id=? AND group_id=?", value(input, "status", "published"), postId, groupId);
        if (input.containsKey("isTop"))
            db.update("UPDATE study_group_posts SET is_top=? WHERE id=? AND group_id=?", Boolean.TRUE.equals(input.get("isTop")), postId, groupId);
        if (input.containsKey("isFeatured"))
            db.update("UPDATE study_group_posts SET is_featured=? WHERE id=? AND group_id=?", Boolean.TRUE.equals(input.get("isFeatured")), postId, groupId);
        log(groupId, actor, "moderate", "post", postId, input.toString());
    }

    public List<Map<String, Object>> announcements(long groupId, Authentication auth) {
        requireView(group(groupId), auth);
        return db.queryForList("SELECT a.*,u.username AS author_username FROM study_group_announcements a JOIN users u ON u.id=a.author_id WHERE a.group_id=? ORDER BY a.created_at DESC", groupId);
    }

    public void announcement(Authentication auth, long groupId, Map<String, Object> input) {
        long user = accounts.id(auth);
        requireManager(groupId, user);
        String title = text(input, "title"), content = text(input, "content");
        if (title.isBlank() || content.isBlank()) bad("公告标题和内容不能为空");
        db.update("INSERT INTO study_group_announcements(group_id,author_id,title,content) VALUES(?,?,?,?)", groupId, user, title, content);
        notifyMembers(groupId, "小组发布了新公告：" + title);
        log(groupId, user, "announcement", "announcement", 0, title);
    }

    public List<Map<String, Object>> files(long groupId, Authentication auth) {
        requireView(group(groupId), auth);
        return db.queryForList("SELECT f.*,u.username AS uploader_username FROM study_group_files f JOIN users u ON u.id=f.uploader_id WHERE f.group_id=? ORDER BY f.created_at DESC", groupId);
    }

    public void file(Authentication auth, long groupId, Map<String, Object> input) {
        long user = accounts.id(auth);
        requireMember(groupId, user);
        String name = text(input, "name"), path = text(input, "path");
        if (name.isBlank() || path.isBlank()) bad("文件名和路径不能为空");
        db.update("INSERT INTO study_group_files(group_id,uploader_id,name,path,size_bytes,allow_download) VALUES(?,?,?,?,?,?)", groupId, user, name, path, numberOr(input, "sizeBytes", 0), !Boolean.FALSE.equals(input.get("allowDownload")));
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
        List<Map<String, Object>> rows = db.queryForList("SELECT * FROM study_group_files WHERE id=? AND group_id=?", fileId, groupId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资料不存在");
        Map<String, Object> row = rows.getFirst();
        if (rowNumber(row, "uploader_id") != user) requireManager(groupId, user);
        db.update("DELETE FROM study_group_files WHERE id=?", fileId);
        removeStoredFile(String.valueOf(row.get("path")));
        log(groupId, user, "file_delete", "file", fileId, String.valueOf(row.get("name")));
    }

    public List<Map<String, Object>> checkins(long groupId, Authentication auth) {
        requireView(group(groupId), auth);
        Long user = userOrNull(auth);
        String select = "SELECT c.*,u.username AS creator_username,(SELECT COUNT(*) FROM study_group_checkin_records r WHERE r.checkin_id=c.id) AS completed_count" + (user == null ? ",FALSE AS completed" : " ,EXISTS(SELECT 1 FROM study_group_checkin_records r2 WHERE r2.checkin_id=c.id AND r2.user_id=?) AS completed") + " FROM study_group_checkins c JOIN users u ON u.id=c.creator_id WHERE c.group_id=? ORDER BY c.created_at DESC";
        return user == null ? db.queryForList(select, groupId) : db.queryForList(select, user, groupId);
    }

    public void createCheckin(Authentication auth, long groupId, Map<String, Object> input) {
        long user = accounts.id(auth);
        requireManager(groupId, user);
        String title = text(input, "title");
        if (title.isBlank()) bad("打卡任务标题不能为空");
        db.update("INSERT INTO study_group_checkins(group_id,creator_id,title,description,due_at) VALUES(?,?,?,?,?)", groupId, user, title, text(input, "description"), input.get("dueAt"));
        log(groupId, user, "checkin_create", "checkin", 0, title);
    }

    public void completeCheckin(Authentication auth, long groupId, long checkinId, Map<String, Object> input) {
        long user = accounts.id(auth);
        requireMember(groupId, user);
        db.update("INSERT IGNORE INTO study_group_checkin_records(checkin_id,user_id,content) VALUES(?,?,?)", checkinId, user, text(input, "content"));
        db.update("UPDATE study_groups SET activity_score=activity_score+2 WHERE id=?", groupId);
    }

    public void report(Authentication auth, long groupId, Map<String, Object> input) {
        long user = accounts.id(auth);
        requireMember(groupId, user);
        db.update("INSERT INTO study_group_reports(group_id,reporter_id,target_type,target_id,reason,description) VALUES(?,?,?,?,?,?)", groupId, user, value(input, "targetType", "group"), numberOr(input, "targetId", groupId), text(input, "reason"), text(input, "description"));
        notifyManagers(groupId, "小组收到新的违规内容举报");
    }

    public List<Map<String, Object>> reports(Authentication auth, long groupId) {
        long user = accounts.id(auth);
        requireManager(groupId, user);
        return db.queryForList("SELECT r.*,u.username AS reporter_username FROM study_group_reports r JOIN users u ON u.id=r.reporter_id WHERE r.group_id=? ORDER BY r.status,r.created_at DESC", groupId);
    }

    public void handleReport(Authentication auth, long groupId, long reportId, Map<String, Object> input) {
        long user = accounts.id(auth);
        requireManager(groupId, user);
        String status = value(input, "status", "");
        if (!Set.of("resolved", "rejected").contains(status)) bad("举报状态无效");
        db.update("UPDATE study_group_reports SET status=?,handler_id=?,handled_at=NOW() WHERE id=? AND group_id=?", status, user, reportId, groupId);
        log(groupId, user, "report_" + status, "report", reportId, text(input, "note"));
    }

    public List<Map<String, Object>> requests(Authentication auth, long groupId) {
        long user = accounts.id(auth);
        requireManager(groupId, user);
        return db.queryForList("SELECT r.*,u.username FROM study_group_join_requests r JOIN users u ON u.id=r.user_id WHERE r.group_id=? ORDER BY r.status,r.created_at DESC", groupId);
    }

    public void reviewRequest(Authentication auth, long groupId, long requestId, Map<String, Object> input) {
        long actor = accounts.id(auth);
        requireManager(groupId, actor);
        String status = value(input, "status", "");
        if (!Set.of("approved", "rejected").contains(status)) bad("审核状态无效");
        List<Map<String, Object>> rows = db.queryForList("SELECT * FROM study_group_join_requests WHERE id=? AND group_id=? AND status='pending'", requestId, groupId);
        if (rows.isEmpty()) bad("申请不存在或已处理");
        long user = rowNumber(rows.getFirst(), "user_id");
        // 同一位用户在同一小组只保留一条申请记录，防止历史 approved/rejected 记录撞唯一键
        db.update("DELETE FROM study_group_join_requests WHERE group_id=? AND user_id=? AND id<>?", groupId, user, requestId);
        db.update("UPDATE study_group_join_requests SET status=?,reviewer_id=?,handled_at=NOW() WHERE id=?", status, actor, requestId);
        if ("approved".equals(status) && member(groupId, user) == null) addMember(groupId, user, "member");
        db.update("INSERT INTO notifications(user_id,kind,message) VALUES(?,?,?)", user, "group", "小组加入申请已" + (("approved".equals(status)) ? "通过" : "拒绝"));
        log(groupId, actor, "review", "join_request", requestId, status);
    }

    public List<Map<String, Object>> adminGroups(Authentication auth, String keyword) {
        accounts.admin(auth);
        String q = keyword.trim();
        return db.queryForList("SELECT g.*,u.username AS owner_username FROM study_groups g JOIN users u ON u.id=g.owner_id WHERE ?='' OR LOCATE(LOWER(?),LOWER(g.name))>0 OR LOCATE(LOWER(?),LOWER(g.description))>0 ORDER BY g.created_at DESC", q, q, q);
    }

    public void adminUpdate(Authentication auth, long groupId, Map<String, Object> input) {
        accounts.admin(auth);
        group(groupId);
        String status = value(input, "status", "");
        if (!Set.of("active", "disabled").contains(status)) bad("小组状态无效");
        db.update("UPDATE study_groups SET status=? WHERE id=?", status, groupId);
        log(groupId, accounts.id(auth), "admin_status", "group", groupId, status);
    }

    public List<Map<String, Object>> logs(Authentication auth, Long groupId) {
        accounts.admin(auth);
        if (groupId == null)
            return db.queryForList("SELECT l.*,u.username AS actor_username,g.name AS group_name FROM study_group_logs l LEFT JOIN users u ON u.id=l.actor_id JOIN study_groups g ON g.id=l.group_id ORDER BY l.created_at DESC LIMIT 200");
        return db.queryForList("SELECT l.*,u.username AS actor_username,g.name AS group_name FROM study_group_logs l LEFT JOIN users u ON u.id=l.actor_id JOIN study_groups g ON g.id=l.group_id WHERE l.group_id=? ORDER BY l.created_at DESC", groupId);
    }

    private List<Map<String, Object>> posts(long groupId, Authentication auth, int limit) {
        Long user = userOrNull(auth);
        String sql = "SELECT p.*,u.username AS author_username,(SELECT COUNT(*) FROM study_group_post_replies r WHERE r.post_id=p.id) AS reply_count,(SELECT COUNT(*) FROM study_group_post_likes l WHERE l.post_id=p.id) AS like_count,(SELECT COUNT(*) FROM study_group_post_favorites f WHERE f.post_id=p.id) AS favorite_count" + (user == null ? ",FALSE AS liked,FALSE AS favorited" : " ,EXISTS(SELECT 1 FROM study_group_post_likes l2 WHERE l2.post_id=p.id AND l2.user_id=?) AS liked,EXISTS(SELECT 1 FROM study_group_post_favorites f2 WHERE f2.post_id=p.id AND f2.user_id=?) AS favorited") + " FROM study_group_posts p JOIN users u ON u.id=p.author_id WHERE p.group_id=? AND p.status='published' ORDER BY p.is_top DESC,p.is_featured DESC,p.created_at DESC LIMIT " + limit;
        return user == null ? db.queryForList(sql, groupId) : db.queryForList(sql, user, user, groupId);
    }

    private Map<String, Object> post(long groupId, long postId, Authentication auth) {
        requireView(group(groupId), auth);
        List<Map<String, Object>> rows = posts(groupId, auth, 1000).stream().filter(p -> rowNumber(p, "id") == postId).toList();
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "帖子不存在");
        Map<String, Object> result = new LinkedHashMap<>(rows.getFirst());
        result.put("replies", db.queryForList("SELECT r.*,u.username AS author_username FROM study_group_post_replies r JOIN users u ON u.id=r.author_id WHERE r.post_id=? ORDER BY r.created_at ASC", postId));
        return result;
    }

    private Map<String, Object> group(long id) {
        List<Map<String, Object>> rows = db.queryForList("SELECT g.*,u.username AS owner_username FROM study_groups g JOIN users u ON u.id=g.owner_id WHERE g.id=?", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "小组不存在");
        return rows.getFirst();
    }

    private List<Map<String, Object>> members(long groupId) {
        return db.queryForList("SELECT m.*,u.username,u.points,u.reputation,(SELECT COUNT(*) FROM study_group_posts p WHERE p.group_id=m.group_id AND p.author_id=m.user_id) AS contribution FROM study_group_members m JOIN users u ON u.id=m.user_id WHERE m.group_id=? ORDER BY FIELD(m.role,'owner','admin','member'),m.joined_at", groupId);
    }

    private Map<String, Object> member(long groupId, long userId) {
        return db.queryForList("SELECT * FROM study_group_members WHERE group_id=? AND user_id=?", groupId, userId).stream().findFirst().orElse(null);
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
        db.update("INSERT IGNORE INTO study_group_members(group_id,user_id,role) VALUES(?,?,?)", groupId, userId, role);
        db.update("UPDATE study_groups SET member_count=(SELECT COUNT(*) FROM study_group_members WHERE group_id=?) WHERE id=?", groupId, groupId);
    }

    private void notifyManagers(long groupId, String message) {
        db.update("INSERT INTO notifications(user_id,kind,message) SELECT user_id,'group',? FROM study_group_members WHERE group_id=? AND role IN ('owner','admin')", message, groupId);
    }

    private void notifyMembers(long groupId, String message) {
        db.update("INSERT INTO notifications(user_id,kind,message) SELECT user_id,'group',? FROM study_group_members WHERE group_id=?", message, groupId);
    }

    private void log(long groupId, long actor, String action, String type, long target, String detail) {
        db.update("INSERT INTO study_group_logs(group_id,actor_id,action,target_type,target_id,detail) VALUES(?,?,?,?,?,?)", groupId, actor, action, type, target, detail == null ? "" : detail);
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
