package com.studentforum.controller;

import com.studentforum.service.GroupService;
import com.studentforum.service.GroupPostService;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/groups")
public class GroupController {
    private final GroupService groupService;
    private final GroupPostService posts;

    public GroupController(GroupService groupService, GroupPostService posts) {
        this.groupService = groupService;
        this.posts = posts;
    }

    @GetMapping("/rankings")
    public List<Map<String, Object>> rankings(@RequestParam(defaultValue = "active") String type) {
        return groupService.rankings(type);
    }

    @GetMapping("/recommended")
    public List<Map<String, Object>> recommended(Authentication auth) {
        return groupService.recommended(auth);
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String tag,
                                          @RequestParam(required = false) String subject,
                                          @RequestParam(required = false) String school,
                                          @RequestParam(defaultValue = "active") String status,
                                          @RequestParam(defaultValue = "activity") String sort) {
        return groupService.list(keyword,tag,subject,school,status,sort);
    }

    @PostMapping
    public Map<String, Object> create(Authentication auth, @RequestBody Map<String, Object> input) {
        return groupService.create(auth,input);
    }

    @GetMapping("/{groupId}")
    public Map<String, Object> detail(@PathVariable long groupId, Authentication auth) {
        return groupService.detail(groupId,auth);
    }

    @PatchMapping("/{groupId}")
    public Map<String, Object> update(Authentication auth, @PathVariable long groupId, @RequestBody Map<String, Object> input) {
        return groupService.update(auth,groupId,input);
    }

    @DeleteMapping("/{groupId}")
    public void dissolve(Authentication auth, @PathVariable long groupId) {
        groupService.dissolve(auth,groupId);
    }

    @PostMapping("/{groupId}/join")
    public Map<String, Object> join(Authentication auth, @PathVariable long groupId) {
        return groupService.join(auth,groupId);
    }

    @PostMapping("/{groupId}/apply")
    public Map<String, Object> apply(Authentication auth, @PathVariable long groupId, @RequestBody(required = false) Map<String, Object> input) {
        return groupService.apply(auth,groupId,input);
    }

    @PostMapping("/{groupId}/invite")
    public void invite(Authentication auth, @PathVariable long groupId, @RequestBody Map<String, Object> input) {
        groupService.invite(auth,groupId,input);
    }

    @PostMapping("/{groupId}/leave")
    public void leave(Authentication auth, @PathVariable long groupId) {
        groupService.leave(auth,groupId);
    }

    @GetMapping("/{groupId}/members")
    public List<Map<String, Object>> memberList(@PathVariable long groupId, Authentication auth) {
        return groupService.memberList(groupId,auth);
    }

    @PatchMapping("/{groupId}/members/{userId}")
    public void memberRole(Authentication auth, @PathVariable long groupId, @PathVariable long userId, @RequestBody Map<String, Object> input) {
        groupService.memberRole(auth,groupId,userId,input);
    }

    @DeleteMapping("/{groupId}/members/{userId}")
    public void removeMember(Authentication auth, @PathVariable long groupId, @PathVariable long userId) {
        groupService.removeMember(auth,groupId,userId);
    }

    @GetMapping("/{groupId}/posts")
    public List<Map<String, Object>> groupPosts(@PathVariable long groupId, Authentication auth, @RequestParam(defaultValue = "") String keyword) {
        return posts.groupPosts(groupId,auth,keyword);
    }

    @PostMapping("/{groupId}/posts")
    public Map<String, Object> createPost(Authentication auth, @PathVariable long groupId, @RequestBody Map<String, Object> input) {
        return posts.createPost(auth,groupId,input);
    }

    @GetMapping("/{groupId}/posts/{postId}")
    public Map<String, Object> postDetail(@PathVariable long groupId, @PathVariable long postId, Authentication auth) {
        return posts.postDetail(groupId,postId,auth);
    }

    @PostMapping("/{groupId}/posts/{postId}/replies")
    public void reply(Authentication auth, @PathVariable long groupId, @PathVariable long postId, @RequestBody Map<String, Object> input) {
        posts.reply(auth,groupId,postId,input);
    }

    @PostMapping("/{groupId}/posts/{postId}/like")
    public Map<String, Object> like(Authentication auth, @PathVariable long groupId, @PathVariable long postId) {
        return posts.like(auth,groupId,postId);
    }

    @PostMapping("/{groupId}/posts/{postId}/favorite")
    public Map<String, Object> favorite(Authentication auth, @PathVariable long groupId, @PathVariable long postId) {
        return posts.favorite(auth,groupId,postId);
    }

    @PatchMapping("/{groupId}/posts/{postId}")
    public void moderatePost(Authentication auth, @PathVariable long groupId, @PathVariable long postId, @RequestBody Map<String, Object> input) {
        posts.moderatePost(auth,groupId,postId,input);
    }

    @GetMapping("/{groupId}/announcements")
    public List<Map<String, Object>> announcements(@PathVariable long groupId, Authentication auth) {
        return groupService.announcements(groupId,auth);
    }

    @PostMapping("/{groupId}/announcements")
    public void announcement(Authentication auth, @PathVariable long groupId, @RequestBody Map<String, Object> input) {
        groupService.announcement(auth,groupId,input);
    }

    @GetMapping("/{groupId}/files")
    public List<Map<String, Object>> files(@PathVariable long groupId, Authentication auth) {
        return groupService.files(groupId,auth);
    }

    @PostMapping("/{groupId}/files")
    public void file(Authentication auth, @PathVariable long groupId, @RequestBody Map<String, Object> input) {
        groupService.file(auth,groupId,input);
    }

    @PostMapping("/{groupId}/files/upload")
    public Map<String, Object> uploadFile(Authentication auth, @PathVariable long groupId, @RequestParam MultipartFile file) throws IOException {
        return groupService.uploadFile(auth,groupId,file);
    }

    @DeleteMapping("/{groupId}/files/{fileId}")
    public void deleteFile(Authentication auth, @PathVariable long groupId, @PathVariable long fileId) {
        groupService.deleteFile(auth,groupId,fileId);
    }

    @GetMapping("/{groupId}/checkin")
    public List<Map<String, Object>> checkins(@PathVariable long groupId, Authentication auth) {
        return groupService.checkins(groupId,auth);
    }

    @PostMapping("/{groupId}/checkin")
    public void createCheckin(Authentication auth, @PathVariable long groupId, @RequestBody Map<String, Object> input) {
        groupService.createCheckin(auth,groupId,input);
    }

    @PostMapping("/{groupId}/checkin/{checkinId}/complete")
    public void completeCheckin(Authentication auth, @PathVariable long groupId, @PathVariable long checkinId, @RequestBody(required = false) Map<String, Object> input) {
        groupService.completeCheckin(auth,groupId,checkinId,input);
    }

    @PostMapping("/{groupId}/reports")
    public void report(Authentication auth, @PathVariable long groupId, @RequestBody Map<String, Object> input) {
        groupService.report(auth,groupId,input);
    }

    @GetMapping("/{groupId}/reports")
    public List<Map<String, Object>> reports(Authentication auth, @PathVariable long groupId) {
        return groupService.reports(auth,groupId);
    }

    @PatchMapping("/{groupId}/reports/{reportId}")
    public void handleReport(Authentication auth, @PathVariable long groupId, @PathVariable long reportId, @RequestBody Map<String, Object> input) {
        groupService.handleReport(auth,groupId,reportId,input);
    }

    @GetMapping("/{groupId}/join-requests")
    public List<Map<String, Object>> requests(Authentication auth, @PathVariable long groupId) {
        return groupService.requests(auth,groupId);
    }

    @PatchMapping("/{groupId}/join-requests/{requestId}")
    public void reviewRequest(Authentication auth, @PathVariable long groupId, @PathVariable long requestId, @RequestBody Map<String, Object> input) {
        groupService.reviewRequest(auth,groupId,requestId,input);
    }

    @GetMapping("/admin")
    public List<Map<String, Object>> adminGroups(Authentication auth, @RequestParam(defaultValue = "") String keyword) {
        return groupService.adminGroups(auth,keyword);
    }

    @PatchMapping("/admin/{groupId}")
    public void adminUpdate(Authentication auth, @PathVariable long groupId, @RequestBody Map<String, Object> input) {
        groupService.adminUpdate(auth,groupId,input);
    }

    @GetMapping("/admin/logs")
    public List<Map<String, Object>> logs(Authentication auth, @RequestParam(required = false) Long groupId) {
        return groupService.logs(auth,groupId);
    }
}
