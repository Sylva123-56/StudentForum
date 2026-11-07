package com.studentforum.service;

import com.studentforum.repository.GroupRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GroupMembershipService {
    private final GroupRepository groups;
    private final AccountAccessService accounts;
    private final GroupAccessService access;

    public GroupMembershipService(GroupRepository groups, AccountAccessService accounts, GroupAccessService access) {
        this.groups = groups;
        this.accounts = accounts;
        this.access = access;
    }

    public Map<String, Object> join(Authentication auth, long groupId) {
        long user = accounts.id(auth);
        Map<String, Object> g = access.group(groupId);
        if (!"active".equals(g.get("status"))) bad("小组不可加入");
        if (access.member(groupId, user) != null) return access.member(groupId, user);
        if (!"public".equals(g.get("visibility")) || !"free".equals(g.get("join_mode")))
            forbidden("该小组需要申请或邀请");
        addMember(groupId, user, "member");
        log(groupId, user, "join", "member", user, "自由加入");
        return access.member(groupId, user);
    }

    public Map<String, Object> apply(Authentication auth, long groupId, Map<String, Object> input) {
        long user = accounts.id(auth);
        Map<String, Object> g = access.group(groupId);
        if (access.member(groupId, user) != null) bad("你已经是小组成员");
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
        access.requireManager(groupId, user);
        long invitee = number(input, "userId");
        if (access.member(groupId, invitee) != null) bad("对方已经是成员");
        addMember(groupId, invitee, "member");
        groups.notifyUser(invitee, "group", "你已被邀请加入小组：" + access.group(groupId).get("name"));
        log(groupId, user, "invite", "member", invitee, "邀请成员");
    }

    public void leave(Authentication auth, long groupId) {
        long user = accounts.id(auth);
        Map<String, Object> m = access.member(groupId, user);
        if (m == null) bad("你不是小组成员");
        if ("owner".equals(m.get("role"))) bad("组长请先转让组长或解散小组");
        groups.removeMember(groupId, user);
        groups.decrementMemberCount(groupId);
        log(groupId, user, "leave", "member", user, "退出小组");
    }

    public List<Map<String, Object>> memberList(long groupId, Authentication auth) {
        Map<String, Object> g = access.group(groupId);
        access.requireView(g, auth);
        return groups.members(groupId);
    }

    public void memberRole(Authentication auth, long groupId, long userId, Map<String, Object> input) {
        long actor = accounts.id(auth);
        Map<String, Object> group = access.group(groupId);
        Map<String, Object> actorMember = access.requireManager(groupId, actor);
        String role = value(input, "role", "");
        if (!Set.of("owner", "admin", "member").contains(role)) bad("成员角色无效");
        if ("owner".equals(role) && !"owner".equals(actorMember.get("role"))) forbidden("仅组长可以转让组长");
        if (!"owner".equals(role) && rowNumber(group, "owner_id") == userId)
            bad("请使用转让组长操作");
        access.requireMember(groupId, userId);
        if ("owner".equals(role)) {
            groups.demoteOwner(groupId);
            groups.transferOwner(userId, groupId);
        }
        groups.setMemberRole(role, groupId, userId);
        log(groupId, actor, "role", "member", userId, "设置角色为" + role);
    }

    public void removeMember(Authentication auth, long groupId, long userId) {
        long actor = accounts.id(auth);
        access.requireManager(groupId, actor);
        Map<String, Object> target = access.requireMember(groupId, userId);
        if ("owner".equals(target.get("role"))) bad("不能移除组长");
        groups.removeMember(groupId, userId);
        groups.decrementMemberCount(groupId);
        log(groupId, actor, "remove", "member", userId, "移除成员");
    }

    public List<Map<String, Object>> requests(Authentication auth, long groupId) {
        long user = accounts.id(auth);
        access.requireManager(groupId, user);
        return groups.joinRequests(groupId);
    }

    public void reviewRequest(Authentication auth, long groupId, long requestId, Map<String, Object> input) {
        long actor = accounts.id(auth);
        access.requireManager(groupId, actor);
        String status = value(input, "status", "");
        if (!Set.of("approved", "rejected").contains(status)) bad("审核状态无效");
        List<Map<String, Object>> rows = groups.reviewableRequest(requestId, groupId);
        if (rows.isEmpty()) bad("申请不存在或已处理");
        long user = rowNumber(rows.getFirst(), "user_id");
        // 同一位用户在同一小组只保留一条申请记录，防止历史 approved/rejected 记录撞唯一键
        groups.removeOlderRequests(groupId, user, requestId);
        groups.reviewRequest(status, actor, requestId);
        if ("approved".equals(status) && access.member(groupId, user) == null) addMember(groupId, user, "member");
        groups.notifyUser(user, "group", "小组加入申请已" + (("approved".equals(status)) ? "通过" : "拒绝"));
        log(groupId, actor, "review", "join_request", requestId, status);
    }

    private void addMember(long groupId, long userId, String role) {
        groups.addMember(groupId, userId, role);
        groups.refreshMemberCount(groupId);
    }

    private void notifyManagers(long groupId, String message) {
        groups.notifyManagers(message, groupId);
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

    private long number(Map<String, Object> input, String key) {
        Object v = input.get(key);
        if (v == null) bad("缺少" + key);
        return Long.parseLong(String.valueOf(v));
    }

    private long rowNumber(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private void bad(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private void forbidden(String message) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
}
