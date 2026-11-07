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
    private final AccountAccessService accounts;

    public GroupAdminService(GroupAdminRepository groups, AccountAccessService accounts) {
        this.groups = groups;
        this.accounts = accounts;
    }

    public List<Map<String, Object>> groups(Authentication auth, String keyword) {
        accounts.admin(auth);
        return groups.groups(keyword.trim());
    }

    public List<Map<String, Object>> reports(Authentication auth, String status) {
        accounts.admin(auth);
        return groups.reports(status);
    }

    public void handleReport(Authentication auth, long reportId, Map<String, Object> input) {
        accounts.admin(auth);
        String status = String.valueOf(input.getOrDefault("status", ""));
        if (!Set.of("resolved", "rejected").contains(status))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "举报状态无效");
        groups.handleReport(reportId, accounts.id(auth), status);
    }

    public void update(Authentication auth, long groupId, Map<String, Object> input) {
        accounts.admin(auth);
        if (!groups.groupExists(groupId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "小组不存在");
        String status = String.valueOf(input.getOrDefault("status", ""));
        if (!Set.of("active", "disabled").contains(status))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "小组状态无效");
        groups.updateStatus(groupId, status);
        groups.log(groupId, accounts.id(auth), "admin_status", "group", groupId, status);
    }

    public List<Map<String, Object>> logs(Authentication auth, Long groupId) {
        accounts.admin(auth);
        return groups.logs(groupId);
    }
}
