package com.studentforum.service;

import com.studentforum.mapper.ForumMapper;
import com.studentforum.repository.VoteRepository;
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
public class VoteService {
    private final VoteRepository repository;
    private final AccountAccessService accounts;
    private final ForumMapper mapper;

    public VoteService(VoteRepository repository, AccountAccessService accounts, ForumMapper mapper) {
        this.repository = repository;
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
    public void createVote(Authentication auth, long postId, Map<String, Object> input) {
        accounts.writable(auth);
        Map<String, Object> post = required(mapper.post(postId));
        check(number(post, "author_id") == id(auth) && "published".equals(post.get("status")), "只能为自己的有效帖子发起投票");
        LocalDateTime closes = parseTime(input.get("closesAt"));
        check(closes.isAfter(LocalDateTime.now()) && closes.isBefore(LocalDateTime.now().plusYears(1)), "截止时间无效");
        String visibility = text(input, "visibility", 20);
        check(Set.of("always", "after_vote", "after_close").contains(visibility), "结果可见性无效");
        check(repository.voteId(postId) == null, "此帖已有投票");
        if (!(input.get("options") instanceof List<?> options) || options.size() < 2 || options.size() > 10)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入 2 至 10 个选项");
        repository.createVote(postId, Boolean.TRUE.equals(input.get("multiple")), closes, visibility);
        long voteId = number(required(repository.voteId(postId)), "id");
        for (Object option : options) {
            check(option instanceof String && !((String) option).isBlank() && ((String) option).length() <= 100, "选项长度无效");
            repository.insertVoteOption(voteId, ((String) option).trim());
        }
    }

    private LocalDateTime parseTime(Object value) {
        try {
            return LocalDateTime.parse(String.valueOf(value));
        } catch (RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "日期格式无效");
        }
    }

    public Map<String, Object> vote(Authentication auth, long postId) {
        check("published".equals(required(mapper.post(postId)).get("status")), "帖子不可见");
        Map<String, Object> vote = new java.util.HashMap<>(required(repository.vote(postId)));
        long me = auth == null || "anonymousUser".equals(auth.getPrincipal()) ? -1 : id(auth), voteId = number(vote, "id");
        boolean voted = repository.ballot(voteId, me) != null;
        boolean visible = "always".equals(vote.get("visibility")) || "after_vote".equals(vote.get("visibility")) && voted || "after_close".equals(vote.get("visibility")) && time(vote.get("closes_at")).isBefore(LocalDateTime.now());
        vote.put("closed", !time(vote.get("closes_at")).isAfter(LocalDateTime.now()));
        vote.put("options", repository.voteOptions(voteId, visible));
        vote.put("voted", voted);
        vote.put("visible", visible);
        return vote;
    }

    @Transactional
    public void ballot(Authentication auth, long postId, Map<String, Object> input) {
        Map<String, Object> vote = required(repository.lockVote(postId));
        check("published".equals(required(mapper.post(postId)).get("status")), "帖子不可见");
        long voteId = number(vote, "id");
        check(time(vote.get("closes_at")).isAfter(LocalDateTime.now()), "投票已截止");
        if (!(input.get("optionIds") instanceof List<?> options) || options.isEmpty() || options.size() > 10 || !Boolean.TRUE.equals(vote.get("multiple")) && options.size() != 1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "选项无效");
        check(repository.ballot(voteId, id(auth)) == null, "已投过票");
        check(options.stream().distinct().count() == options.size(), "选项不能重复");
        for (Object option : options) {
            check(option instanceof Number && repository.voteOption(voteId, ((Number) option).longValue()) != null, "选项无效");
            repository.insertBallot(voteId, ((Number) option).longValue(), id(auth));
        }
    }

    @Transactional
    public void bounty(Authentication auth, long postId, Map<String, Object> input) {
        accounts.writable(auth);
        Map<String, Object> post = required(mapper.post(postId));
        long amount = number(input, "amount");
        check(number(post, "author_id") == id(auth) && "question".equals(post.get("type")) && !Boolean.TRUE.equals(post.get("is_solved")), "只有未解决的本人问答帖可设置悬赏");
        check(amount >= 1 && amount <= 1000 && repository.bountyExists(postId) == null, "悬赏无效");
        check(repository.deductBounty(amount, id(auth)) == 1, "积分不足");
        repository.createBounty(postId, amount);
        mapper.pointLog(id(auth), "bounty", -(int) amount, mapper.points(id(auth)), "post", postId);
    }

    public Map<String, Object> bounty(long postId) {
        return required(repository.bounty(postId));
    }

    private LocalDateTime time(Object value) {
        return value instanceof java.sql.Timestamp timestamp ? timestamp.toLocalDateTime() : (LocalDateTime) value;
    }
}
