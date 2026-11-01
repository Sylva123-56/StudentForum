package com.studentforum.controller;

import com.studentforum.service.AdminService;
import com.studentforum.dto.AdminPostStatusRequest;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AdminService admin;

    public AdminController(AdminService admin) {
        this.admin = admin;
    }

    @GetMapping("/dashboard")
    public Map<String,Object> dashboard(Authentication auth) {
        return admin.dashboard(auth);
    }

    @GetMapping("/users")
    public Map<String,Object> users(Authentication auth,@RequestParam(required=false) String keyword,@RequestParam(defaultValue="1") int page) {
        return admin.users(auth,keyword,page);
    }

    @GetMapping("/posts")
    public Map<String,Object> posts(Authentication auth,@RequestParam(required=false) String keyword,@RequestParam(defaultValue="1") int page) {
        return admin.posts(auth,keyword,page);
    }

    @GetMapping("/reports")
    public Map<String,Object> reports(Authentication auth,@RequestParam(required=false) String keyword,@RequestParam(defaultValue="1") int page) {
        return admin.reports(auth,keyword,page);
    }

    @PatchMapping("/posts/{id}/feature")
    public void feature(Authentication auth,@PathVariable long id,@RequestBody Map<String,Boolean> input) {
        admin.feature(auth,id,input);
    }

    @PatchMapping("/posts/{id}/top")
    public void top(Authentication auth,@PathVariable long id,@RequestBody Map<String,Boolean> input) {
        admin.top(auth,id,input);
    }

    @PatchMapping("/posts/{id}/status")
    public void status(Authentication auth,@PathVariable long id,@RequestBody AdminPostStatusRequest input) {
        admin.status(auth,id,input);
    }

    @PatchMapping("/reports/{id}")
    public void handle(Authentication auth,@PathVariable long id,@RequestBody Map<String,Object> input) {
        admin.handle(auth,id,input);
    }

    @PatchMapping("/users/{id}/status")
    public void userStatus(Authentication auth,@PathVariable long id,@RequestBody Map<String,String> input) {
        admin.userStatus(auth,id,input);
    }

    @PatchMapping("/users/{id}/role")
    public void role(Authentication auth,@PathVariable long id,@RequestBody Map<String,Object> input) {
        admin.role(auth,id,input);
    }

    @PostMapping("/users/{id}/points")
    public void adjust(Authentication auth,@PathVariable long id,@RequestBody Map<String,Object> input) {
        admin.adjust(auth,id,input);
    }

    @GetMapping("/points")
    public Map<String,Object> pointLogs(Authentication auth,@RequestParam(required=false) String keyword,@RequestParam(defaultValue="1") int page) {
        return admin.pointLogs(auth,keyword,page);
    }

    @GetMapping("/boards")
    public Map<String,Object> boardList(Authentication auth,@RequestParam(required=false) String keyword,@RequestParam(defaultValue="1") int page) {
        return admin.boardList(auth,keyword,page);
    }

    @GetMapping("/tags")
    public Map<String,Object> tagList(Authentication auth,@RequestParam(required=false) String keyword,@RequestParam(defaultValue="1") int page) {
        return admin.tagList(auth,keyword,page);
    }

    @PostMapping("/boards")
    public void board(Authentication auth,@RequestBody Map<String,Object> input) {
        admin.board(auth,input);
    }

    @PatchMapping("/boards/{id}")
    public void editBoard(Authentication auth,@PathVariable long id,@RequestBody Map<String,Object> input) {
        admin.editBoard(auth,id,input);
    }

    @PatchMapping("/boards/{id}/status")
    public void boardStatus(Authentication auth,@PathVariable long id,@RequestBody Map<String,String> input) {
        admin.boardStatus(auth,id,input);
    }

    @DeleteMapping("/boards/{id}")
    public void deleteBoard(Authentication auth,@PathVariable long id) {
        admin.deleteBoard(auth,id);
    }

    @PostMapping("/tags")
    public void tag(Authentication auth,@RequestBody Map<String,String> input) {
        admin.tag(auth,input);
    }

    @PatchMapping("/tags/{id}")
    public void editTag(Authentication auth,@PathVariable long id,@RequestBody Map<String,String> input) {
        admin.editTag(auth,id,input);
    }

    @PatchMapping("/tags/{id}/status")
    public void tagStatus(Authentication auth,@PathVariable long id,@RequestBody Map<String,String> input) {
        admin.tagStatus(auth,id,input);
    }

    @DeleteMapping("/tags/{id}")
    public void deleteTag(Authentication auth,@PathVariable long id) {
        admin.deleteTag(auth,id);
    }

    @GetMapping("/announcements")
    public Map<String,Object> announcements(Authentication auth,@RequestParam(defaultValue="1") int page) {
        return admin.announcements(auth,page);
    }

    @PostMapping("/announcements")
    public void announcement(Authentication auth,@RequestBody Map<String,String> input) {
        admin.announcement(auth,input);
    }

    @PatchMapping("/announcements/{id}")
    public void editAnnouncement(Authentication auth,@PathVariable long id,@RequestBody Map<String,String> input) {
        admin.editAnnouncement(auth,id,input);
    }

    @DeleteMapping("/announcements/{id}")
    public void deleteAnnouncement(Authentication auth,@PathVariable long id) {
        admin.deleteAnnouncement(auth,id);
    }
}
