package com.studentforum.service;

import com.studentforum.mapper.ForumMapper;
import com.studentforum.repository.ContentRevisionRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ContentWorkflowService {
    private final ContentRevisionRepository repository;
    private final ForumService service;
    private final VoteService votes;
    private final AccountAccessService accounts;
    private final ForumMapper mapper;

    public ContentWorkflowService(ContentRevisionRepository repository, ForumService service, VoteService votes, AccountAccessService accounts, ForumMapper mapper) {
        this.repository = repository;
        this.service = service;
        this.votes = votes;
        this.accounts = accounts;
        this.mapper = mapper;
    }

    private long id(Authentication auth) {
        return accounts.id(auth);
    }

    private Map<String, Object> required(Map<String, Object> row) {
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "内容不存在");
        return row;
    }

    private long number(Map<String, Object> input, String key) {
        if (!(input.get(key) instanceof Number value))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "参数无效");
        return value.longValue();
    }

    private String text(Map<String, Object> input, String key, int max) {
        String result = input.get(key) instanceof String value ? value.trim() : "";
        if (result.length() > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内容过长");
        return result;
    }

    private void check(boolean allowed, String message) {
        if (!allowed) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    @Transactional
    public Map<String, Object> publish(Authentication auth, Map<String, Object> input) {
        Map<String, Object> post = service.publish(auth, input);
        long postId = number(post, "id");
        if (input.get("vote") instanceof Map<?, ?> vote) {
            Map<String, Object> options = new java.util.HashMap<>();
            vote.forEach((key, value) -> options.put(String.valueOf(key), value));
            votes.createVote(auth, postId, options);
        }
        if (input.get("bounty") instanceof Number amount) {
            check(amount.longValue() >= 0, "悬赏无效");
            if (amount.longValue() > 0) votes.bounty(auth, postId, Map.of("amount", amount));
        }
        return mapper.post(postId);
    }

    public List<Map<String, Object>> revisions(long postId) {
        check("published".equals(required(mapper.post(postId)).get("status")), "帖子不可见");
        return repository.postRevisions(postId);
    }

    public Map<String, Object> revision(long postId, long revisionId) {
        check("published".equals(required(mapper.post(postId)).get("status")), "帖子不可见");
        return required(repository.postRevision(postId, revisionId));
    }

    public List<Map<String, Object>> replyRevisions(long replyId) {
        Map<String, Object> reply = required(mapper.reply(replyId));
        check("published".equals(reply.get("status")) && "published".equals(required(mapper.post(number(reply, "post_id"))).get("status")), "回复不可见");
        return repository.replyRevisions(replyId);
    }

    @Transactional
    public void editReply(Authentication auth, long replyId, Map<String, Object> input) {
        Map<String, Object> reply = required(mapper.reply(replyId));
        check(number(reply, "author_id") == id(auth) && "published".equals(reply.get("status")), "只能编辑自己的回复");
        String content = text(input, "content", 5000);
        com.studentforum.model.ContentRules.text(content, 2, 5000);
        repository.recordReplyRevision(replyId, id(auth), reply.get("content"));
        repository.updateReply(content, replyId);
    }
}
