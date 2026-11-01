package com.studentforum.controller;

import com.studentforum.service.AdminStudyService;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/study-tools")
public class AdminStudyController {
    private final AdminStudyService adminStudyService;

    public AdminStudyController(AdminStudyService adminStudyService) {
        this.adminStudyService = adminStudyService;
    }

    @GetMapping("/checkin")
    public Map<String, Object> checkin(Authentication auth) {
        return adminStudyService.checkin(auth);
    }

    @PatchMapping("/checkin/settings")
    public Map<String, Object> saveSettings(Authentication auth, @RequestBody Map<String, Object> input) {
        return adminStudyService.saveSettings(auth,input);
    }

    @GetMapping("/focus")
    public Map<String, Object> focus(Authentication auth, @RequestParam(defaultValue = "week") String range) {
        return adminStudyService.focus(auth,range);
    }

    @DeleteMapping("/focus/{sessionId}")
    public Map<String, Object> deleteSession(Authentication auth, @PathVariable long sessionId) {
        return adminStudyService.deleteSession(auth,sessionId);
    }

    @GetMapping("/study-rooms")
    public Map<String, Object> rooms(Authentication auth,
                              @RequestParam(required = false) String status,
                              @RequestParam(required = false) String keyword) {
        return adminStudyService.rooms(auth,status,keyword);
    }

    @PatchMapping("/study-rooms/{roomId}")
    public Map<String, Object> updateRoom(Authentication auth, @PathVariable long roomId, @RequestBody Map<String, Object> input) {
        return adminStudyService.updateRoom(auth,roomId,input);
    }

    @DeleteMapping("/study-rooms/{roomId}")
    public Map<String, Object> deleteRoom(Authentication auth, @PathVariable long roomId) {
        return adminStudyService.deleteRoom(auth,roomId);
    }
}
