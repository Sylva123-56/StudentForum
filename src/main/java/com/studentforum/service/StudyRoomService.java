package com.studentforum.service;

import com.studentforum.repository.StudyRoomRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 自习室：创建/加入/退出、在线成员与专注时长、文字鼓励、静音、排行榜。
 * 自习室只做"一起专注"的场域，不存学习内容，所以房间本身可以自由关闭不必留档。
 */
@Service
public class StudyRoomService {
    private static final Set<String> VISIBILITIES = Set.of("public", "private");
    private static final Set<String> KINDS = Set.of("text", "encourage", "system");
    private static final int MAX_MESSAGES = 50;

    private final StudyRoomRepository repository;
    private final AccountAccessService accounts;
    private final StudyToolService tools;
    private final StudyPreferencesService preferences;
    private final StudyRoomAccessService roomAccess;
    private final BadgeService badges;

    public StudyRoomService(StudyRoomRepository repository, AccountAccessService accounts, StudyToolService tools, StudyPreferencesService preferences, StudyRoomAccessService roomAccess, BadgeService badges) {
        this.repository = repository;
        this.accounts = accounts;
        this.tools = tools;
        this.preferences = preferences;
        this.roomAccess = roomAccess;
        this.badges = badges;
    }

    private long id(Authentication auth) {
        return accounts.id(auth);
    }

    /**
     * 自习室对游客开放围观，所以这里不能直接用 {@link AccountAccessService#id}：未登录时 Spring 传进来的是
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

    private Map<String, Object> required(Map<String, Object> row, String message) {
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
        return row;
    }

    private void check(boolean condition, String message) {
        if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private Map<String, Object> room(long roomId, long user) {
        Map<String, Object> room = required(repository.findWithOwner(roomId), "自习室不存在");
        roomAccess.requireRoomView(room, user);
        room.put("joined", repository.membership(roomId, user).size() > 0);
        room.put("online", repository.onlineMembers(roomId));
        room.put("groupName", room.get("group_id") == null ? null : groupName(tools.rowNumber(room, "group_id")));
        return room;
    }

    private String groupName(long groupId) {
        Map<String, Object> group = repository.groupName(groupId);
        return group == null ? null : String.valueOf(group.get("name"));
    }

    // ---------- 房间 ----------

    public Map<String, Object> rooms(Authentication auth, String scope) {
        Long user = viewer(auth);
        check(scope != null && Set.of("mine", "joined", "host", "all").contains(scope), "查询范围无效");
        if (!"all".equals(scope)) check(user != null, "请先登录");
        List<Map<String, Object>> items = repository.rooms(user, scope);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("scope", scope);
        if (user != null) result.put("mine", repository.myRooms(user, user));
        result.put("groups", user == null ? List.of() : preferences.myGroups(user));
        return result;
    }

    @Transactional
    public Map<String, Object> createRoom(Authentication auth, Map<String, Object> input) {
        accounts.writable(auth);
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
        if (groupId != null && groupId > 0) check(repository.groupMembership(groupId, user) != null, "需要先加入该学习小组");
        else groupId = null;

        long roomId = repository.insertRoom(user, name, goal == null ? "" : goal, subject == null ? "" : subject, visibility, muted, minMinutes, groupId);
        repository.addOwner(roomId, user);
        repository.initializeMemberCount(roomId);

        // 关联小组时给组员发一条入房通知，让自习室有人气
        if (groupId != null) {
            List<Map<String, Object>> members = repository.otherGroupMembers(groupId, user);
            for (Map<String, Object> member : members) {
                repository.notifyRoomOpened(tools.rowNumber(member, "user_id"), "学习小组「" + groupName(groupId) + "」开了一间自习室：" + name);
            }
        }
        badges.evaluateBadges(user);
        return room(roomId, user);
    }

    public Map<String, Object> room(Authentication auth, long roomId) {
        long user = guest(auth);
        Map<String, Object> result = room(roomId, user);
        // 房间消息默认就带回来，前端不必再发一次请求
        result.put("messages", repository.messages(roomId, MAX_MESSAGES));
        result.put("leaderboard", leaderboardRows(roomId));
        return result;
    }

    @Transactional
    public Map<String, Object> join(Authentication auth, long roomId) {
        accounts.writable(auth);
        long user = id(auth);
        Map<String, Object> row = required(repository.findRoom(roomId), "自习室不存在");
        check("active".equals(row.get("status")), "自习室已关闭");
        roomAccess.requireRoomView(row, user);
        if (repository.membership(roomId, user).isEmpty()) {
            repository.addMember(roomId, user);
            roomAccess.refreshRoomTotals(roomId);
        }
        return room(roomId, user);
    }

    @Transactional
    public Map<String, Object> leave(Authentication auth, long roomId) {
        accounts.writable(auth);
        long user = id(auth);
        Map<String, Object> row = required(repository.findRoom(roomId), "自习室不存在");
        check(tools.rowNumber(row, "owner_id") != user, "房主不能退出自己的自习室，可以直接关闭");
        repository.removeMember(roomId, user);
        roomAccess.refreshRoomTotals(roomId);
        return Map.of("left", true);
    }

    @Transactional
    public Map<String, Object> updateRoom(Authentication auth, long roomId, Map<String, Object> input) {
        accounts.writable(auth);
        long user = id(auth);
        Map<String, Object> row = required(repository.findRoom(roomId), "自习室不存在");
        roomAccess.requireRoomOwner(row, user);
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

        repository.updateRoom(name, goal == null ? "" : goal, subject == null ? "" : subject, visibility, muted, status, minMinutes, roomId);
        if ("closed".equals(status)) {
            // 关闭房间时广播一条系统消息，参与者知道为什么房间没了
            repository.recordOwnerClose(roomId, user);
        }
        return room(roomId, user);
    }

    @Transactional
    public Map<String, Object> deleteRoom(Authentication auth, long roomId) {
        accounts.writable(auth);
        long user = id(auth);
        Map<String, Object> row = required(repository.findRoom(roomId), "自习室不存在");
        roomAccess.requireRoomOwner(row, user);
        repository.deleteRoom(roomId);
        return Map.of("deleted", true);
    }

    // ---------- 消息与排行 ----------

    public Map<String, Object> messages(Authentication auth, long roomId) {
        long user = guest(auth);
        Map<String, Object> row = required(repository.findRoom(roomId), "自习室不存在");
        roomAccess.requireRoomView(row, user);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", repository.messages(roomId, MAX_MESSAGES));
        result.put("muted", tools.rowFlag(row, "muted"));
        result.put("online", repository.activeMembers(roomId));
        return result;
    }

    /** 发文字鼓励。静音房间里只有房主能说话，避免打扰正在专注的人。 */
    @Transactional
    public Map<String, Object> sendMessage(Authentication auth, long roomId, Map<String, Object> input) {
        accounts.writable(auth);
        long user = id(auth);
        Map<String, Object> row = required(repository.findRoom(roomId), "自习室不存在");
        roomAccess.requireRoomView(row, user);
        boolean host = tools.rowNumber(row, "owner_id") == user;
        check(!tools.rowFlag(row, "muted") || host, "房间已开启静音，只有房主可以发言");
        check(repository.membership(roomId, user).size() > 0, "请先加入自习室再发言");
        String content = tools.text(input, "content", 1, 200, "发言需要 1 到 200 个字");
        String kind = input.get("kind") == null ? "text" : String.valueOf(input.get("kind"));
        check(KINDS.contains(kind) && !"system".equals(kind), "消息类型无效");
        long messageId = repository.insertMessage(roomId, user, kind, content);
        repository.touchMember(roomId, user);
        return required(repository.findMessage(messageId), "消息不存在");
    }

    public Map<String, Object> deleteMessage(Authentication auth, long roomId, long messageId) {
        accounts.writable(auth);
        long user = id(auth);
        Map<String, Object> room = required(repository.findRoom(roomId), "自习室不存在");
        Map<String, Object> message = required(repository.findRoomMessage(messageId, roomId), "消息不存在");
        boolean host = tools.rowNumber(room, "owner_id") == user;
        check(host || tools.rowNumber(message, "user_id") == user, "只能删除自己的发言");
        repository.deleteMessage(messageId);
        return Map.of("deleted", true);
    }

    /** 自习室里的专注时长榜（房间内排名），和全站排行是两套口径。 */
    public Map<String, Object> leaderboard(Authentication auth, long roomId) {
        long user = guest(auth);
        Map<String, Object> row = required(repository.findRoom(roomId), "自习室不存在");
        roomAccess.requireRoomView(row, user);
        return Map.of("items", leaderboardRows(roomId));
    }

    private List<Map<String, Object>> leaderboardRows(long roomId) {
        return repository.leaderboard(roomId);
    }

    /** 学习小组成员一起进的自习室：给小组页一个"我们的自习室"入口。 */
    public Map<String, Object> forGroup(Authentication auth, long groupId) {
        long user = guest(auth);
        check(repository.groupMembership(groupId, user) != null, "需要先加入该学习小组");
        return Map.of("items", repository.groupRooms(groupId));
    }
}
