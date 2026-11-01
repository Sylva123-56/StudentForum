package com.studentforum.controller;

import com.studentforum.service.GroupAdminService;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/groups")
public class GroupAdminController {
    private final GroupAdminService groupAdminService;

    public GroupAdminController(GroupAdminService groupAdminService) {
        this.groupAdminService = groupAdminService;
    }

    @GetMapping
    public List<Map<String, Object>> groups(Authentication auth, @RequestParam(defaultValue = "") String keyword) {
        return groupAdminService.groups(auth,keyword);
    }

    @GetMapping("/reports")
    public List<Map<String, Object>> reports(Authentication auth, @RequestParam(defaultValue = "pending") String status) {
        return groupAdminService.reports(auth,status);
    }

    @PatchMapping("/reports/{reportId}")
    public void handleReport(Authentication auth, @PathVariable long reportId, @RequestBody Map<String, Object> input) {
        groupAdminService.handleReport(auth,reportId,input);
    }

    @PatchMapping("/{groupId}")
    public void update(Authentication auth, @PathVariable long groupId, @RequestBody Map<String, Object> input) {
        groupAdminService.update(auth,groupId,input);
    }

    @GetMapping("/logs")
    public List<Map<String, Object>> logs(Authentication auth, @RequestParam(required = false) Long groupId) {
        return groupAdminService.logs(auth,groupId);
    }
}
