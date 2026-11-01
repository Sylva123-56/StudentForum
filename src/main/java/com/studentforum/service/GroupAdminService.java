package com.studentforum.service;

import com.studentforum.repository.GroupAdminRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GroupAdminService {
    private final GroupAdminRepository groups;
    private final ForumService forum;

    public GroupAdminService(GroupAdminRepository groups, ForumService forum) {
        this.groups = groups;
        this.forum = forum;
    }

    public List<Map<String, Object>> groups(Authentication auth, String keyword) {
        forum.admin(auth);
        return groups.groups(keyword.trim());
    }

    public List<Map<String, Object>> reports(Authentication auth, String status) {
        forum.admin(auth);
        return groups.reports(status);
    }

    public void handleReport(Authentication auth, long reportId, Map<String, Object> input) {
        forum.admin(auth);
        String status = String.valueOf(input.getOrDefault("status", ""));
        if (!Set.of("resolved", "rejected").contains(status))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "举报状态无效");
        groups.handleReport(reportId, forum.id(auth), status);
    }

    public void update(Authentication auth, long groupId, Map<String, Object> input) {
        forum.admin(auth);
        if (!groups.groupExists(groupId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "小组不存在");
        String status = String.valueOf(input.getOrDefault("status", ""));
        if (!Set.of("active", "disabled").contains(status))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "小组状态无效");
        groups.updateStatus(groupId, status);
        groups.log(groupId, forum.id(auth), "admin_status", "group", groupId, status);
    }

    public List<Map<String, Object>> logs(Authentication auth, Long groupId) {
        forum.admin(auth);
        return groups.logs(groupId);
    }
}
