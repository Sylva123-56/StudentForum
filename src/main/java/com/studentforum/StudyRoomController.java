package com.studentforum;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 自习室：创建/加入/退出、在线成员与专注时长、文字鼓励、静音、排行榜。
 * 自习室只做"一起专注"的场域，不存学习内容，所以房间本身可以自由关闭不必留档。
 */
@RestController
@RequestMapping("/api/study-rooms")
class StudyRoomController {
    private static final Set<String> VISIBILITIES = Set.of("public", "private");
    private static final Set<String> KINDS = Set.of("text", "encourage", "system");
    private static final int MAX_MESSAGES = 50;

    private final JdbcTemplate db;
    private final ForumService service;
    private final StudyToolService tools;

    StudyRoomController(JdbcTemplate db, ForumService service, StudyToolService tools) {
        this.db = db;
        this.service = service;
        this.tools = tools;
    }

    private long id(Authentication auth) {
        return service.id(auth);
    }

    /**
     * 自习室对游客开放围观，所以这里不能直接用 {@link ForumService#id}：未登录时 Spring 传进来的是
     * 匿名身份（不是 null），直接调用会抛 401。游客返回 null，登录用户返回真实 id。
     */
    private Long viewer(Authentication auth) {
        return auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal()) ? null : id(auth);
    }

    /** 游客视角的 user 占位值（0 不与任何真实用户冲突，用来判断"只能看公开房间"）。 */
    private long guest(Authentication auth) {
        Long user = viewer(auth);
        return user == null ? 0 : user;
    }

    private void check(boolean condition, String message) {
        if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private Map<String, Object> room(long roomId, long user) {
        Map<String, Object> room = tools.requiredRow("SELECT r.*,u.username AS owner_username FROM study_rooms r JOIN users u ON u.id=r.owner_id WHERE r.id=?", "自习室不存在", roomId);
        tools.requireRoomView(room, user);
        room.put("joined", db.queryForList("SELECT id FROM study_room_members WHERE room_id=? AND user_id=?", roomId, user).size() > 0);
        room.put("online", db.queryForList("SELECT m.user_id,u.username,m.total_minutes,m.joined_at,m.last_active_at FROM study_room_members m JOIN users u ON u.id=m.user_id WHERE m.room_id=? ORDER BY m.total_minutes DESC LIMIT 50", roomId));
        room.put("groupName", room.get("group_id") == null ? null : groupName(tools.rowNumber(room, "group_id")));
        return room;
    }

    private String groupName(long groupId) {
        Map<String, Object> group = tools.row("SELECT name FROM study_groups WHERE id=?", groupId);
        return group == null ? null : String.valueOf(group.get("name"));
    }

    // ---------- 房间 ----------

    @GetMapping
    Map<String, Object> rooms(Authentication auth, @RequestParam(defaultValue = "all") String scope) {
        Long user = viewer(auth);
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT r.*,u.username AS owner_username,(SELECT COUNT(*) FROM study_room_members m WHERE m.room_id=r.id) AS members FROM study_rooms r JOIN users u ON u.id=r.owner_id WHERE 1=1");
        boolean mine = "mine".equals(scope) || "joined".equals(scope);
        if (mine) {
            check(user != null, "请先登录");
            sql.append(" AND (r.owner_id=? OR r.id IN (SELECT room_id FROM study_room_members WHERE user_id=?))");
            args.add(user);
            args.add(user);
        } else if ("host".equals(scope)) {
            check(user != null, "请先登录");
            sql.append(" AND r.owner_id=?");
            args.add(user);
        } else {
            // 默认只逛公开自习室；私密房间只能靠房主邀请/直链进入
            check("all".equals(scope), "查询范围无效");
            sql.append(" AND r.visibility='public'");
        }
        sql.append(" AND r.status='active' ORDER BY r.member_count DESC,r.created_at DESC LIMIT 60");
        List<Map<String, Object>> items = db.queryForList(sql.toString(), args.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("scope", scope);
        if (user != null) result.put("mine", db.queryForList("SELECT r.id,r.name,r.status,r.member_count,r.focus_minutes,m.total_minutes FROM study_rooms r LEFT JOIN study_room_members m ON m.room_id=r.id AND m.user_id=? WHERE r.owner_id=? OR m.user_id IS NOT NULL ORDER BY r.created_at DESC LIMIT 20", user, user));
        result.put("groups", user == null ? List.of() : tools.myGroups(user));
        return result;
    }

    @PostMapping
    @Transactional
    Map<String, Object> createRoom(Authentication auth, @RequestBody Map<String, Object> input) {
        service.writable(auth);
        long user = id(auth);
        String name = tools.text(input, "name", 2, 40, "自习室名称需要 2 到 40 个字");
        String goal = tools.optionalText(input, "goal", 120);
        String subject = tools.optionalText(input, "subject", 40);
        String visibility = input.get("visibility") == null ? "public" : String.valueOf(input.get("visibility"));
        check(VISIBILITIES.contains(visibility), "自习室可见性无效");
        int minMinutes = (int) tools.number(input, "minMinutes", 0);
        check(minMinutes >= 0 && minMinutes <= 720, "单次最少专注时长需要在 0 到 720 分钟之间");
        boolean muted = Boolean.TRUE.equals(input.get("muted")) || "true".equals(String.valueOf(input.get("muted")));
        Long groupId = input.get("groupId") == null ? null : tools.number(input, "groupId", 0);
        if (groupId != null && groupId > 0) check(tools.row("SELECT 1 FROM study_group_members WHERE group_id=? AND user_id=?", groupId, user) != null, "需要先加入该学习小组");
        else groupId = null;

        db.update("INSERT INTO study_rooms(owner_id,name,goal,subject,visibility,status,muted,min_minutes,group_id,member_count) VALUES(?,?,?,?,?,'active',?,?,?,0)",
                user, name, goal == null ? "" : goal, subject == null ? "" : subject, visibility, muted, minMinutes, groupId);
        long roomId = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        db.update("INSERT INTO study_room_members(room_id,user_id,role,total_minutes,joined_at,last_active_at) VALUES(?,?,'owner',0,NOW(),NOW())", roomId, user);
        db.update("UPDATE study_rooms SET member_count=1 WHERE id=?", roomId);

        // 关联小组时给组员发一条入房通知，让自习室有人气
        if (groupId != null) {
            List<Map<String, Object>> members = db.queryForList("SELECT user_id FROM study_group_members WHERE group_id=? AND user_id<>?", groupId, user);
            for (Map<String, Object> member : members) {
                db.update("INSERT INTO notifications(user_id,kind,message) VALUES(?,'room',?)", tools.rowNumber(member, "user_id"), "学习小组「" + groupName(groupId) + "」开了一间自习室：" + name);
            }
        }
        tools.evaluateBadges(user);
        return room(roomId, user);
    }

    @GetMapping("/{roomId}")
    Map<String, Object> room(Authentication auth, @PathVariable long roomId) {
        long user = guest(auth);
        Map<String, Object> result = room(roomId, user);
        // 房间消息默认就带回来，前端不必再发一次请求
        result.put("messages", db.queryForList("SELECT m.id,m.kind,m.content,m.created_at,m.user_id,u.username FROM study_room_messages m JOIN users u ON u.id=m.user_id WHERE m.room_id=? ORDER BY m.id DESC LIMIT ?", roomId, MAX_MESSAGES));
        result.put("leaderboard", leaderboardRows(roomId));
        return result;
    }

    @PostMapping("/{roomId}/join")
    @Transactional
    Map<String, Object> join(Authentication auth, @PathVariable long roomId) {
        service.writable(auth);
        long user = id(auth);
        Map<String, Object> row = tools.requiredRow("SELECT * FROM study_rooms WHERE id=?", "自习室不存在", roomId);
        check("active".equals(row.get("status")), "自习室已关闭");
        tools.requireRoomView(row, user);
        if (db.queryForList("SELECT id FROM study_room_members WHERE room_id=? AND user_id=?", roomId, user).isEmpty()) {
            db.update("INSERT INTO study_room_members(room_id,user_id,role,total_minutes,joined_at,last_active_at) VALUES(?,?,'member',0,NOW(),NOW())", roomId, user);
            tools.refreshRoomTotals(roomId);
        }
        return room(roomId, user);
    }

    @PostMapping("/{roomId}/leave")
    @Transactional
    Map<String, Object> leave(Authentication auth, @PathVariable long roomId) {
        service.writable(auth);
        long user = id(auth);
        Map<String, Object> row = tools.requiredRow("SELECT * FROM study_rooms WHERE id=?", "自习室不存在", roomId);
        check(tools.rowNumber(row, "owner_id") != user, "房主不能退出自己的自习室，可以直接关闭");
        db.update("DELETE FROM study_room_members WHERE room_id=? AND user_id=?", roomId, user);
        tools.refreshRoomTotals(roomId);
        return Map.of("left", true);
    }

    @PatchMapping("/{roomId}")
    @Transactional
    Map<String, Object> updateRoom(Authentication auth, @PathVariable long roomId, @RequestBody Map<String, Object> input) {
        service.writable(auth);
        long user = id(auth);
        Map<String, Object> row = tools.requiredRow("SELECT * FROM study_rooms WHERE id=?", "自习室不存在", roomId);
        tools.requireRoomOwner(row, user);
        String name = input.containsKey("name") ? tools.text(input, "name", 2, 40, "自习室名称需要 2 到 40 个字") : String.valueOf(row.get("name"));
        String goal = input.containsKey("goal") ? tools.optionalText(input, "goal", 120) : (String) row.get("goal");
        String subject = input.containsKey("subject") ? tools.optionalText(input, "subject", 40) : (String) row.get("subject");
        String visibility = input.containsKey("visibility") ? String.valueOf(input.get("visibility")) : String.valueOf(row.get("visibility"));
        check(VISIBILITIES.contains(visibility), "自习室可见性无效");
        boolean muted = input.containsKey("muted") ? Boolean.TRUE.equals(input.get("muted")) || "true".equals(String.valueOf(input.get("muted"))) : tools.rowFlag(row, "muted");
        String status = input.containsKey("status") ? String.valueOf(input.get("status")) : String.valueOf(row.get("status"));
        check("active".equals(status) || "closed".equals(status), "自习室状态无效");
        int minMinutes = input.containsKey("minMinutes") ? (int) tools.number(input, "minMinutes", 0) : (int) tools.rowNumber(row, "min_minutes");
        check(minMinutes >= 0 && minMinutes <= 720, "单次最少专注时长需要在 0 到 720 分钟之间");

        db.update("UPDATE study_rooms SET name=?,goal=?,subject=?,visibility=?,muted=?,status=?,min_minutes=? WHERE id=?",
                name, goal == null ? "" : goal, subject == null ? "" : subject, visibility, muted, status, minMinutes, roomId);
        if ("closed".equals(status)) {
            // 关闭房间时广播一条系统消息，参与者知道为什么房间没了
            db.update("INSERT INTO study_room_messages(room_id,user_id,kind,content) VALUES(?,?,'system','房主关闭了自习室')", roomId, user);
        }
        return room(roomId, user);
    }

    @DeleteMapping("/{roomId}")
    @Transactional
    Map<String, Object> deleteRoom(Authentication auth, @PathVariable long roomId) {
        service.writable(auth);
        long user = id(auth);
        Map<String, Object> row = tools.requiredRow("SELECT * FROM study_rooms WHERE id=?", "自习室不存在", roomId);
        tools.requireRoomOwner(row, user);
        db.update("DELETE FROM study_rooms WHERE id=?", roomId);
        return Map.of("deleted", true);
    }

    // ---------- 消息与排行 ----------

    @GetMapping("/{roomId}/messages")
    Map<String, Object> messages(Authentication auth, @PathVariable long roomId) {
        long user = guest(auth);
        Map<String, Object> row = tools.requiredRow("SELECT * FROM study_rooms WHERE id=?", "自习室不存在", roomId);
        tools.requireRoomView(row, user);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", db.queryForList("SELECT m.id,m.kind,m.content,m.created_at,m.user_id,u.username FROM study_room_messages m JOIN users u ON u.id=m.user_id WHERE m.room_id=? ORDER BY m.id DESC LIMIT ?", roomId, MAX_MESSAGES));
        result.put("muted", tools.rowFlag(row, "muted"));
        result.put("online", db.queryForList("SELECT m.user_id,u.username,m.total_minutes,m.last_active_at FROM study_room_members m JOIN users u ON u.id=m.user_id WHERE m.room_id=? ORDER BY m.total_minutes DESC LIMIT 50", roomId));
        return result;
    }

    /** 发文字鼓励。静音房间里只有房主能说话，避免打扰正在专注的人。 */
    @PostMapping("/{roomId}/messages")
    @Transactional
    Map<String, Object> sendMessage(Authentication auth, @PathVariable long roomId, @RequestBody Map<String, Object> input) {
        service.writable(auth);
        long user = id(auth);
        Map<String, Object> row = tools.requiredRow("SELECT * FROM study_rooms WHERE id=?", "自习室不存在", roomId);
        tools.requireRoomView(row, user);
        boolean host = tools.rowNumber(row, "owner_id") == user;
        check(!tools.rowFlag(row, "muted") || host, "房间已开启静音，只有房主可以发言");
        check(db.queryForList("SELECT id FROM study_room_members WHERE room_id=? AND user_id=?", roomId, user).size() > 0, "请先加入自习室再发言");
        String content = tools.text(input, "content", 1, 200, "发言需要 1 到 200 个字");
        String kind = input.get("kind") == null ? "text" : String.valueOf(input.get("kind"));
        check(KINDS.contains(kind) && !"system".equals(kind), "消息类型无效");
        db.update("INSERT INTO study_room_messages(room_id,user_id,kind,content) VALUES(?,?,?,?)", roomId, user, kind, content);
        db.update("UPDATE study_room_members SET last_active_at=NOW() WHERE room_id=? AND user_id=?", roomId, user);
        long messageId = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return tools.requiredRow("SELECT m.id,m.kind,m.content,m.created_at,m.user_id,u.username FROM study_room_messages m JOIN users u ON u.id=m.user_id WHERE m.id=?", "消息不存在", messageId);
    }

    @DeleteMapping("/{roomId}/messages/{messageId}")
    Map<String, Object> deleteMessage(Authentication auth, @PathVariable long roomId, @PathVariable long messageId) {
        service.writable(auth);
        long user = id(auth);
        Map<String, Object> room = tools.requiredRow("SELECT * FROM study_rooms WHERE id=?", "自习室不存在", roomId);
        Map<String, Object> message = tools.requiredRow("SELECT * FROM study_room_messages WHERE id=? AND room_id=?", "消息不存在", messageId, roomId);
        boolean host = tools.rowNumber(room, "owner_id") == user;
        check(host || tools.rowNumber(message, "user_id") == user, "只能删除自己的发言");
        db.update("DELETE FROM study_room_messages WHERE id=?", messageId);
        return Map.of("deleted", true);
    }

    /** 自习室里的专注时长榜（房间内排名），和全站排行是两套口径。 */
    @GetMapping("/{roomId}/leaderboard")
    Map<String, Object> leaderboard(Authentication auth, @PathVariable long roomId) {
        long user = guest(auth);
        Map<String, Object> row = tools.requiredRow("SELECT * FROM study_rooms WHERE id=?", "自习室不存在", roomId);
        tools.requireRoomView(row, user);
        return Map.of("items", leaderboardRows(roomId));
    }

    private List<Map<String, Object>> leaderboardRows(long roomId) {
        return db.queryForList("SELECT m.user_id,u.username,m.role,m.total_minutes,m.joined_at FROM study_room_members m JOIN users u ON u.id=m.user_id WHERE m.room_id=? ORDER BY m.total_minutes DESC,u.username LIMIT 50", roomId);
    }

    /** 学习小组成员一起进的自习室：给小组页一个"我们的自习室"入口。 */
    @GetMapping("/for-group/{groupId}")
    Map<String, Object> forGroup(Authentication auth, @PathVariable long groupId) {
        long user = guest(auth);
        check(tools.row("SELECT 1 FROM study_group_members WHERE group_id=? AND user_id=?", groupId, user) != null, "需要先加入该学习小组");
        return Map.of("items", db.queryForList("SELECT r.*,u.username AS owner_username FROM study_rooms r JOIN users u ON u.id=r.owner_id WHERE r.group_id=? AND r.status='active' ORDER BY r.created_at DESC LIMIT 20", groupId));
    }
}
