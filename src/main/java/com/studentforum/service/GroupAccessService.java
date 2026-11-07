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
public class GroupAccessService {
    private final GroupRepository groups;

    public GroupAccessService(GroupRepository groups) {
        this.groups = groups;
    }

    Map<String, Object> group(long id) {
        List<Map<String, Object>> rows = groups.findGroup(id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "小组不存在");
        return rows.getFirst();
    }

    Map<String, Object> member(long groupId, long userId) {
        return groups.membership(groupId, userId).stream().findFirst().orElse(null);
    }

    Map<String, Object> requireMember(long groupId, long userId) {
        Map<String, Object> m = member(groupId, userId);
        if (m == null) forbidden("请先加入小组");
        return m;
    }

    void requireView(Map<String, Object> group, Authentication auth) {
        if ("private".equals(group.get("visibility"))) {
            Long user = userOrNull(auth);
            if (user == null || member(rowNumber(group, "id"), user) == null) forbidden("这是私密小组，请先加入后查看");
        }
    }

    Map<String, Object> requireManager(long groupId, long userId) {
        Map<String, Object> m = requireMember(groupId, userId);
        if (!Set.of("owner", "admin").contains(m.get("role"))) forbidden("仅组长或管理员可以操作");
        return m;
    }

    Long userOrNull(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) return null;
        return Long.parseLong(auth.getName());
    }

    private long rowNumber(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private void forbidden(String message) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
}
