package com.studentforum.controller;

import com.studentforum.service.SocialService;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class V2Controller {
    private final SocialService socialService;

    public V2Controller(SocialService socialService) {
        this.socialService = socialService;
    }

    @PostMapping("/follows/{userId}")
    public void follow(Authentication auth, @PathVariable long userId) {
        socialService.follow(auth,userId);
    }

    @DeleteMapping("/follows/{userId}")
    public void unfollow(Authentication auth, @PathVariable long userId) {
        socialService.unfollow(auth,userId);
    }

    @GetMapping("/me/following")
    public List<Map<String, Object>> following(Authentication auth) {
        return socialService.following(auth);
    }

    @GetMapping("/me/followers")
    public List<Map<String, Object>> followers(Authentication auth) {
        return socialService.followers(auth);
    }

    @GetMapping("/users/{userId}/social")
    public Map<String, Object> social(Authentication auth, @PathVariable long userId) {
        return socialService.social(auth,userId);
    }

    @GetMapping("/feed/following")
    public List<Map<String, Object>> feed(Authentication auth) {
        return socialService.feed(auth);
    }

    @PostMapping("/me/tags/{tagId}/follow")
    public void followTag(Authentication auth, @PathVariable long tagId) {
        socialService.followTag(auth,tagId);
    }

    @DeleteMapping("/me/tags/{tagId}/follow")
    public void unfollowTag(Authentication auth, @PathVariable long tagId) {
        socialService.unfollowTag(auth,tagId);
    }

    @GetMapping("/me/tags/following")
    public List<Map<String, Object>> followingTags(Authentication auth) {
        return socialService.followingTags(auth);
    }

    @PutMapping("/me/notifications/settings")
    public void settings(Authentication auth, @RequestBody Map<String, Object> input) {
        socialService.settings(auth,input);
    }

    @PostMapping("/users/{userId}/block")
    public void block(Authentication auth, @PathVariable long userId) {
        socialService.block(auth,userId);
    }

    @DeleteMapping("/users/{userId}/block")
    public void unblock(Authentication auth, @PathVariable long userId) {
        socialService.unblock(auth,userId);
    }

    @GetMapping("/conversations")
    public List<Map<String, Object>> conversations(Authentication auth) {
        return socialService.conversations(auth);
    }

    @GetMapping("/me/message-recipients")
    public List<Map<String, Object>> messageRecipients(Authentication auth, @RequestParam String keyword) {
        return socialService.messageRecipients(auth,keyword);
    }

    @PostMapping("/conversations")
    public Map<String, Object> conversation(Authentication auth, @RequestBody Map<String, Object> input) {
        return socialService.conversation(auth,input);
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public List<Map<String, Object>> messages(Authentication auth, @PathVariable long conversationId) {
        return socialService.messages(auth,conversationId);
    }

    @PostMapping("/conversations/{conversationId}/messages")
    public void message(Authentication auth, @PathVariable long conversationId, @RequestBody Map<String, Object> input) {
        socialService.message(auth,conversationId,input);
    }

    @PatchMapping("/conversations/{conversationId}/mute")
    public void mute(Authentication auth, @PathVariable long conversationId, @RequestBody Map<String, Object> input) {
        socialService.mute(auth,conversationId,input);
    }

    @PostMapping("/messages/{messageId}/report")
    public void reportMessage(Authentication auth, @PathVariable long messageId, @RequestBody Map<String, Object> input) {
        socialService.reportMessage(auth,messageId,input);
    }
}
