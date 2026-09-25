package com.studentforum;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class V2ContentController {
    private final JdbcTemplate db;
    private final ForumService service;
    private final ForumMapper mapper;

    V2ContentController(JdbcTemplate db, ForumService service, ForumMapper mapper) {
        this.db = db;
        this.service = service;
        this.mapper = mapper;
    }

    private long id(Authentication auth) {
        return service.id(auth);
    }

    private Map<String, Object> row(String sql, Object... args) {
        return db.queryForList(sql, args).stream().findFirst().orElse(null);
    }

    private Map<String, Object> required(String sql, Object... args) {
        return service.require(row(sql, args));
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

    @PostMapping("/posts/enhanced")
    @Transactional
    Map<String, Object> publish(Authentication auth, @RequestBody Map<String, Object> input) {
        Map<String, Object> post = service.publish(auth, input);
        long postId = service.number(post, "id");
        if (input.get("vote") instanceof Map<?, ?> vote) {
            Map<String, Object> options = new java.util.HashMap<>();
            vote.forEach((key, value) -> options.put(String.valueOf(key), value));
            createVote(auth, postId, options);
        }
        if (input.get("bounty") instanceof Number amount) {
            check(amount.longValue() >= 0, "悬赏无效");
            if (amount.longValue() > 0) bounty(auth, postId, Map.of("amount", amount));
        }
        return mapper.post(postId);
    }

    @PostMapping("/posts/{postId}/vote")
    @Transactional
    void createVote(Authentication auth, @PathVariable long postId, @RequestBody Map<String, Object> input) {
        service.writable(auth);
        Map<String, Object> post = service.require(mapper.post(postId));
        check(service.number(post, "author_id") == id(auth) && "published".equals(post.get("status")), "只能为自己的有效帖子发起投票");
        LocalDateTime closes = parseTime(input.get("closesAt"));
        check(closes.isAfter(LocalDateTime.now()) && closes.isBefore(LocalDateTime.now().plusYears(1)), "截止时间无效");
        String visibility = text(input, "visibility", 20);
        check(Set.of("always", "after_vote", "after_close").contains(visibility), "结果可见性无效");
        check(row("SELECT id FROM votes WHERE post_id=?", postId) == null, "此帖已有投票");
        if (!(input.get("options") instanceof List<?> options) || options.size() < 2 || options.size() > 10)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入 2 至 10 个选项");
        db.update("INSERT INTO votes(post_id,multiple,closes_at,visibility) VALUES(?,?,?,?)", postId, Boolean.TRUE.equals(input.get("multiple")), closes, visibility);
        long voteId = service.number(required("SELECT id FROM votes WHERE post_id=?", postId), "id");
        for (Object option : options) {
            check(option instanceof String && !((String) option).isBlank() && ((String) option).length() <= 100, "选项长度无效");
            db.update("INSERT INTO vote_options(vote_id,label) VALUES(?,?)", voteId, ((String) option).trim());
        }
    }

    private LocalDateTime parseTime(Object value) {
        try {
            return LocalDateTime.parse(String.valueOf(value));
        } catch (RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "日期格式无效");
        }
    }

    @GetMapping("/posts/{postId}/vote")
    Map<String, Object> vote(Authentication auth, @PathVariable long postId) {
        check("published".equals(service.require(mapper.post(postId)).get("status")), "帖子不可见");
        Map<String, Object> vote = new java.util.HashMap<>(required("SELECT * FROM votes WHERE post_id=?", postId));
        long me = auth == null || "anonymousUser".equals(auth.getPrincipal()) ? -1 : id(auth), voteId = service.number(vote, "id");
        boolean voted = row("SELECT 1 FROM vote_ballots WHERE vote_id=? AND user_id=?", voteId, me) != null;
        boolean visible = "always".equals(vote.get("visibility")) || "after_vote".equals(vote.get("visibility")) && voted || "after_close".equals(vote.get("visibility")) && time(vote.get("closes_at")).isBefore(LocalDateTime.now());
        vote.put("closed", !time(vote.get("closes_at")).isAfter(LocalDateTime.now()));
        vote.put("options", db.queryForList("SELECT o.id,o.label," + (visible ? "(SELECT COUNT(DISTINCT user_id) FROM vote_ballots WHERE option_id=o.id)" : "0") + " ballots FROM vote_options o WHERE o.vote_id=?", voteId));
        vote.put("voted", voted);
        vote.put("visible", visible);
        return vote;
    }

    @PostMapping("/posts/{postId}/vote/ballots")
    @Transactional
    void ballot(Authentication auth, @PathVariable long postId, @RequestBody Map<String, Object> input) {
        Map<String, Object> vote = required("SELECT * FROM votes WHERE post_id=? FOR UPDATE", postId);
        check("published".equals(service.require(mapper.post(postId)).get("status")), "帖子不可见");
        long voteId = service.number(vote, "id");
        check(time(vote.get("closes_at")).isAfter(LocalDateTime.now()), "投票已截止");
        if (!(input.get("optionIds") instanceof List<?> options) || options.isEmpty() || options.size() > 10 || !Boolean.TRUE.equals(vote.get("multiple")) && options.size() != 1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "选项无效");
        check(row("SELECT 1 FROM vote_ballots WHERE vote_id=? AND user_id=?", voteId, id(auth)) == null, "已投过票");
        check(options.stream().distinct().count() == options.size(), "选项不能重复");
        for (Object option : options) {
            check(option instanceof Number && row("SELECT 1 FROM vote_options WHERE vote_id=? AND id=?", voteId, ((Number) option).longValue()) != null, "选项无效");
            db.update("INSERT INTO vote_ballots(vote_id,option_id,user_id) VALUES(?,?,?)", voteId, ((Number) option).longValue(), id(auth));
        }
    }

    @PostMapping("/posts/{postId}/bounty")
    @Transactional
    void bounty(Authentication auth, @PathVariable long postId, @RequestBody Map<String, Object> input) {
        service.writable(auth);
        Map<String, Object> post = service.require(mapper.post(postId));
        long amount = number(input, "amount");
        check(service.number(post, "author_id") == id(auth) && "question".equals(post.get("type")) && !Boolean.TRUE.equals(post.get("is_solved")), "只有未解决的本人问答帖可设置悬赏");
        check(amount >= 1 && amount <= 1000 && row("SELECT 1 FROM bounties WHERE post_id=?", postId) == null, "悬赏无效");
        check(db.update("UPDATE users SET points=points-? WHERE id=? AND points>=?", amount, id(auth), amount) == 1, "积分不足");
        db.update("INSERT INTO bounties(post_id,amount) VALUES(?,?)", postId, amount);
        mapper.pointLog(id(auth), "bounty", -(int) amount, mapper.points(id(auth)), "post", postId);
    }

    @GetMapping("/posts/{postId}/bounty")
    Map<String, Object> bounty(@PathVariable long postId) {
        return required("SELECT * FROM bounties WHERE post_id=?", postId);
    }

    @GetMapping("/me/drafts")
    List<Map<String, Object>> drafts(Authentication auth) {
        return db.queryForList("SELECT * FROM drafts WHERE user_id=? ORDER BY updated_at DESC LIMIT 100", id(auth));
    }

    @GetMapping("/drafts/{draftId}")
    Map<String, Object> draft(Authentication auth, @PathVariable long draftId) {
        return required("SELECT * FROM drafts WHERE id=? AND user_id=?", draftId, id(auth));
    }

    private LocalDateTime time(Object value) {
        return value instanceof java.sql.Timestamp timestamp ? timestamp.toLocalDateTime() : (LocalDateTime) value;
    }

    @PostMapping("/drafts")
    @Transactional
    Map<String, Object> draft(Authentication auth, @RequestBody Map<String, Object> input) {
        long me = id(auth);
        org.springframework.jdbc.support.GeneratedKeyHolder key = new org.springframework.jdbc.support.GeneratedKeyHolder();
        db.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO drafts(user_id,content) VALUES(?,'')", java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, me);
            return statement;
        }, key);
        long draftId = key.getKey().longValue();
        updateDraft(auth, draftId, input);
        return required("SELECT * FROM drafts WHERE id=?", draftId);
    }

    @PatchMapping("/drafts/{draftId}")
    void updateDraft(Authentication auth, @PathVariable long draftId, @RequestBody Map<String, Object> input) {
        required("SELECT id FROM drafts WHERE id=? AND user_id=?", draftId, id(auth));
        String type = text(input, "type", 20), title = text(input, "title", 160), content = text(input, "content", 10000);
        if (!title.isEmpty()) service.text(title, 1, 160);
        if (!content.isEmpty()) service.text(content, 1, 10000);
        check(Set.of("question", "discussion", "experience").contains(type), "帖子类型无效");
        Long board = input.get("boardId") instanceof Number value ? value.longValue() : null;
        if (board != null) service.require(mapper.board(board));
        String tags = text(input, "tagIds", 255), image = service.image(input.get("imagePath")), cover = service.image(input.get("coverPath")), attachment = service.attachment(input.get("attachmentPath"));
        check(tags.isEmpty() || tags.matches("[0-9]+(,[0-9]+)*"), "标签格式无效");
        LocalDateTime scheduled = input.get("scheduledAt") == null || "".equals(input.get("scheduledAt")) ? null : parseTime(input.get("scheduledAt"));
        if (scheduled != null) {
            check(scheduled.isAfter(LocalDateTime.now()), "定时发布时间必须在未来");
            service.text(title, 5, 160);
            service.text(content, 10, 10000);
            check(board != null, "定时发布需要板块");
        }
        db.update("UPDATE drafts SET board_id=?,type=?,title=?,content=?,image_path=?,cover_path=?,attachment_path=?,tag_ids=?,scheduled_at=? WHERE id=? AND user_id=?", board, type, title, content, image, cover, attachment, tags, scheduled, draftId, id(auth));
    }

    @DeleteMapping("/drafts/{draftId}")
    void deleteDraft(Authentication auth, @PathVariable long draftId) {
        db.update("DELETE FROM drafts WHERE id=? AND user_id=?", draftId, id(auth));
    }

    @GetMapping("/posts/{postId}/revisions")
    List<Map<String, Object>> revisions(@PathVariable long postId) {
        check("published".equals(service.require(mapper.post(postId)).get("status")), "帖子不可见");
        return db.queryForList("SELECT id,title,created_at FROM post_revisions WHERE post_id=? ORDER BY id DESC", postId);
    }

    @GetMapping("/posts/{postId}/revisions/{revisionId}")
    Map<String, Object> revision(@PathVariable long postId, @PathVariable long revisionId) {
        check("published".equals(service.require(mapper.post(postId)).get("status")), "帖子不可见");
        return required("SELECT * FROM post_revisions WHERE post_id=? AND id=?", postId, revisionId);
    }

    @GetMapping("/replies/{replyId}/revisions")
    List<Map<String, Object>> replyRevisions(@PathVariable long replyId) {
        Map<String, Object> reply = service.require(mapper.reply(replyId));
        check("published".equals(reply.get("status")) && "published".equals(service.require(mapper.post(service.number(reply, "post_id"))).get("status")), "回复不可见");
        return db.queryForList("SELECT * FROM reply_revisions WHERE reply_id=? ORDER BY id DESC", replyId);
    }

    @PatchMapping("/replies/{replyId}")
    @Transactional
    void editReply(Authentication auth, @PathVariable long replyId, @RequestBody Map<String, Object> input) {
        Map<String, Object> reply = service.require(mapper.reply(replyId));
        check(service.number(reply, "author_id") == id(auth) && "published".equals(reply.get("status")), "只能编辑自己的回复");
        String content = text(input, "content", 5000);
        service.text(content, 2, 5000);
        db.update("INSERT INTO reply_revisions(reply_id,editor_id,content) VALUES(?,?,?)", replyId, id(auth), reply.get("content"));
        db.update("UPDATE replies SET content=? WHERE id=?", content, replyId);
    }

    @GetMapping("/me/reputation")
    Map<String, Object> reputation(Authentication auth) {
        return Map.of("score", service.current(auth).get("reputation"), "logs", db.queryForList("SELECT * FROM reputation_logs WHERE user_id=? ORDER BY id DESC LIMIT 100", id(auth)));
    }

    @PostMapping("/appeals")
    @Transactional
    void appeal(Authentication auth, @RequestBody Map<String, Object> input) {
        String type = text(input, "targetType", 20), reason = text(input, "reason", 2000);
        long target = number(input, "targetId");
        check(Set.of("post", "reply", "user", "report", "message").contains(type) && reason.length() >= 10, "申诉内容无效");
        if ("post".equals(type)) {
            Map<String, Object> post = service.require(mapper.post(target));
            check(service.number(post, "author_id") == id(auth) && Set.of("hidden", "deleted").contains(post.get("status")) && row("SELECT 1 FROM admin_logs WHERE target_type='post' AND target_id=? AND action IN ('status','report_hide','report_delete')", target) != null, "只能申诉被处理的本人内容");
        }
        if ("reply".equals(type)) {
            Map<String, Object> reply = service.require(mapper.reply(target));
            check(service.number(reply, "author_id") == id(auth) && "deleted".equals(reply.get("status")) && row("SELECT 1 FROM admin_logs WHERE target_type='reply' AND target_id=? AND action='report_delete'", target) != null, "只能申诉被处理的本人内容");
        }
        if ("user".equals(type))
            check(target == id(auth) && !"active".equals(service.current(auth).get("status")), "只能申诉被处理的本人账号");
        if ("report".equals(type)) {
            Map<String, Object> report = service.require(mapper.reportById(target));
            check("resolved".equals(report.get("status")), "举报尚未处理");
            String targetType = String.valueOf(report.get("target_type"));
            long targetId = service.number(report, "target_id");
            check("user".equals(targetType) ? targetId == id(auth) : "post".equals(targetType) ? service.number(service.require(mapper.post(targetId)), "author_id") == id(auth) : "reply".equals(targetType) ? service.number(service.require(mapper.reply(targetId)), "author_id") == id(auth) : false, "不能申诉该举报");
        }
        if ("message".equals(type)) {
            Map<String, Object> message = required("SELECT sender_id,content FROM messages WHERE id=?", target);
            check(service.number(message, "sender_id") == id(auth) && "[已删除]".equals(message.get("content")), "只能申诉被处理的本人消息");
        }
        check(row("SELECT 1 FROM appeals WHERE user_id=? AND target_type=? AND target_id=? AND status='pending'", id(auth), type, target) == null, "该对象已有待处理申诉");
        db.update("INSERT INTO appeals(user_id,target_type,target_id,reason) VALUES(?,?,?,?)", id(auth), type, target, reason);
    }

    @GetMapping("/me/appeals")
    List<Map<String, Object>> appeals(Authentication auth) {
        return db.queryForList("SELECT * FROM appeals WHERE user_id=? ORDER BY id DESC LIMIT 100", id(auth));
    }

    @GetMapping("/admin/appeals")
    List<Map<String, Object>> adminAppeals(Authentication auth) {
        service.admin(auth);
        return db.queryForList("SELECT a.*,u.username FROM appeals a JOIN users u ON u.id=a.user_id ORDER BY (a.status='pending') DESC,a.id DESC LIMIT 100");
    }

    @PatchMapping("/admin/appeals/{appealId}")
    @Transactional
    void handleAppeal(Authentication auth, @PathVariable long appealId, @RequestBody Map<String, Object> input) {
        service.admin(auth);
        String status = text(input, "status", 20), note = text(input, "note", 500);
        check(Set.of("resolved", "rejected").contains(status) && !note.isBlank(), "处理结果及备注不能为空");
        Map<String, Object> appeal = required("SELECT * FROM appeals WHERE id=?", appealId);
        check(db.update("UPDATE appeals SET status=?,handle_note=?,handler_id=?,handled_at=NOW() WHERE id=? AND status='pending'", status, note, id(auth), appealId) == 1, "申诉已处理");
        String message = "申诉已" + ("resolved".equals(status) ? "处理" : "驳回") + "：" + note;
        mapper.notifyUser(service.number(appeal, "user_id"), "appeal", message.substring(0, Math.min(255, message.length())), null);
        mapper.adminLog(id(auth), "appeal_" + status, "appeal", appealId, note);
    }

    @GetMapping("/admin/reputation")
    List<Map<String, Object>> adminReputation(Authentication auth) {
        service.admin(auth);
        return db.queryForList("SELECT r.*,u.username FROM reputation_logs r JOIN users u ON u.id=r.user_id ORDER BY r.id DESC LIMIT 100");
    }

    @PatchMapping("/admin/reputation/{userId}")
    @Transactional
    void adjustReputation(Authentication auth, @PathVariable long userId, @RequestBody Map<String, Object> input) {
        service.admin(auth);
        service.require(mapper.user(userId));
        long amount = number(input, "amount");
        String reason = text(input, "reason", 255);
        check(amount >= -100 && amount <= 100 && amount != 0 && reason.length() >= 3, "调整范围或理由无效");
        service.changeReputation(userId, id(auth), (int) amount, reason);
        mapper.adminLog(id(auth), "reputation", "user", userId, reason);
    }
}
