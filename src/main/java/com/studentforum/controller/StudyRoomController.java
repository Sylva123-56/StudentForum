package com.studentforum.controller;

import com.studentforum.service.StudyRoomService;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/study-rooms")
public class StudyRoomController {
    private final StudyRoomService studyRoomService;

    public StudyRoomController(StudyRoomService studyRoomService) {
        this.studyRoomService = studyRoomService;
    }

    @GetMapping
    public Map<String, Object> rooms(Authentication auth, @RequestParam(defaultValue = "all") String scope) {
        return studyRoomService.rooms(auth,scope);
    }

    @PostMapping
    public Map<String, Object> createRoom(Authentication auth, @RequestBody Map<String, Object> input) {
        return studyRoomService.createRoom(auth,input);
    }

    @GetMapping("/{roomId}")
    public Map<String, Object> room(Authentication auth, @PathVariable long roomId) {
        return studyRoomService.room(auth,roomId);
    }

    @PostMapping("/{roomId}/join")
    public Map<String, Object> join(Authentication auth, @PathVariable long roomId) {
        return studyRoomService.join(auth,roomId);
    }

    @PostMapping("/{roomId}/leave")
    public Map<String, Object> leave(Authentication auth, @PathVariable long roomId) {
        return studyRoomService.leave(auth,roomId);
    }

    @PatchMapping("/{roomId}")
    public Map<String, Object> updateRoom(Authentication auth, @PathVariable long roomId, @RequestBody Map<String, Object> input) {
        return studyRoomService.updateRoom(auth,roomId,input);
    }

    @DeleteMapping("/{roomId}")
    public Map<String, Object> deleteRoom(Authentication auth, @PathVariable long roomId) {
        return studyRoomService.deleteRoom(auth,roomId);
    }

    @GetMapping("/{roomId}/messages")
    public Map<String, Object> messages(Authentication auth, @PathVariable long roomId) {
        return studyRoomService.messages(auth,roomId);
    }

    @PostMapping("/{roomId}/messages")
    public Map<String, Object> sendMessage(Authentication auth, @PathVariable long roomId, @RequestBody Map<String, Object> input) {
        return studyRoomService.sendMessage(auth,roomId,input);
    }

    @DeleteMapping("/{roomId}/messages/{messageId}")
    public Map<String, Object> deleteMessage(Authentication auth, @PathVariable long roomId, @PathVariable long messageId) {
        return studyRoomService.deleteMessage(auth,roomId,messageId);
    }

    @GetMapping("/{roomId}/leaderboard")
    public Map<String, Object> leaderboard(Authentication auth, @PathVariable long roomId) {
        return studyRoomService.leaderboard(auth,roomId);
    }

    @GetMapping("/for-group/{groupId}")
    public Map<String, Object> forGroup(Authentication auth, @PathVariable long groupId) {
        return studyRoomService.forGroup(auth,groupId);
    }
}
