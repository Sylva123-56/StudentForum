package com.studentforum.controller;

import com.studentforum.service.ContentWorkflowService;
import com.studentforum.service.VoteService;
import com.studentforum.service.DraftService;
import com.studentforum.service.AppealService;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class V2ContentController {
    private final ContentWorkflowService contentWorkflowService;
    private final VoteService votes;
    private final DraftService drafts;
    private final AppealService appeals;

    public V2ContentController(ContentWorkflowService contentWorkflowService, VoteService votes, DraftService drafts, AppealService appeals) {
        this.contentWorkflowService = contentWorkflowService;
        this.votes = votes;
        this.drafts = drafts;
        this.appeals = appeals;
    }

    @PostMapping("/posts/enhanced")
    public Map<String, Object> publish(Authentication auth, @RequestBody Map<String, Object> input) {
        return contentWorkflowService.publish(auth,input);
    }

    @PostMapping("/posts/{postId}/vote")
    public void createVote(Authentication auth, @PathVariable long postId, @RequestBody Map<String, Object> input) {
        votes.createVote(auth,postId,input);
    }

    @GetMapping("/posts/{postId}/vote")
    public Map<String, Object> vote(Authentication auth, @PathVariable long postId) {
        return votes.vote(auth,postId);
    }

    @PostMapping("/posts/{postId}/vote/ballots")
    public void ballot(Authentication auth, @PathVariable long postId, @RequestBody Map<String, Object> input) {
        votes.ballot(auth,postId,input);
    }

    @PostMapping("/posts/{postId}/bounty")
    public void bounty(Authentication auth, @PathVariable long postId, @RequestBody Map<String, Object> input) {
        votes.bounty(auth,postId,input);
    }

    @GetMapping("/posts/{postId}/bounty")
    public Map<String, Object> bounty(@PathVariable long postId) {
        return votes.bounty(postId);
    }

    @GetMapping("/me/drafts")
    public List<Map<String, Object>> drafts(Authentication auth) {
        return drafts.drafts(auth);
    }

    @GetMapping("/drafts/{draftId}")
    public Map<String, Object> draft(Authentication auth, @PathVariable long draftId) {
        return drafts.draft(auth,draftId);
    }

    @PostMapping("/drafts")
    public Map<String, Object> draft(Authentication auth, @RequestBody Map<String, Object> input) {
        return drafts.draft(auth,input);
    }

    @PatchMapping("/drafts/{draftId}")
    public void updateDraft(Authentication auth, @PathVariable long draftId, @RequestBody Map<String, Object> input) {
        drafts.updateDraft(auth,draftId,input);
    }

    @DeleteMapping("/drafts/{draftId}")
    public void deleteDraft(Authentication auth, @PathVariable long draftId) {
        drafts.deleteDraft(auth,draftId);
    }

    @GetMapping("/posts/{postId}/revisions")
    public List<Map<String, Object>> revisions(@PathVariable long postId) {
        return contentWorkflowService.revisions(postId);
    }

    @GetMapping("/posts/{postId}/revisions/{revisionId}")
    public Map<String, Object> revision(@PathVariable long postId, @PathVariable long revisionId) {
        return contentWorkflowService.revision(postId,revisionId);
    }

    @GetMapping("/replies/{replyId}/revisions")
    public List<Map<String, Object>> replyRevisions(@PathVariable long replyId) {
        return contentWorkflowService.replyRevisions(replyId);
    }

    @PatchMapping("/replies/{replyId}")
    public void editReply(Authentication auth, @PathVariable long replyId, @RequestBody Map<String, Object> input) {
        contentWorkflowService.editReply(auth,replyId,input);
    }

    @GetMapping("/me/reputation")
    public Map<String, Object> reputation(Authentication auth) {
        return appeals.reputation(auth);
    }

    @GetMapping("/me/appeal-targets")
    public List<Map<String, Object>> appealTargets(Authentication auth) {
        return appeals.appealTargets(auth);
    }

    @PostMapping("/appeals")
    public void appeal(Authentication auth, @RequestBody Map<String, Object> input) {
        appeals.appeal(auth,input);
    }

    @GetMapping("/me/appeals")
    public List<Map<String, Object>> appeals(Authentication auth) {
        return appeals.appeals(auth);
    }

    @GetMapping("/admin/appeals")
    public Map<String, Object> adminAppeals(Authentication auth, @RequestParam(required = false) String keyword, @RequestParam(defaultValue = "1") int page) {
        return appeals.adminAppeals(auth,keyword,page);
    }

    @PatchMapping("/admin/appeals/{appealId}")
    public void handleAppeal(Authentication auth, @PathVariable long appealId, @RequestBody Map<String, Object> input) {
        appeals.handleAppeal(auth,appealId,input);
    }

    @GetMapping("/admin/reputation")
    public Map<String, Object> adminReputation(Authentication auth, @RequestParam(required = false) String keyword, @RequestParam(defaultValue = "1") int page) {
        return appeals.adminReputation(auth,keyword,page);
    }

    @PatchMapping("/admin/reputation/{userId}")
    public void adjustReputation(Authentication auth, @PathVariable long userId, @RequestBody Map<String, Object> input) {
        appeals.adjustReputation(auth,userId,input);
    }
}
