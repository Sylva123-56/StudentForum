package com.studentforum.service;

import com.studentforum.mapper.ForumMapper;
import com.studentforum.repository.AppealRepository;
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
public class AppealService {
    private final AppealRepository repository;
    private final AccountAccessService accounts;
    private final PointService points;
    private final NotificationService notifications;
    private final ForumMapper mapper;

    public AppealService(AppealRepository repository, AccountAccessService accounts, PointService points, NotificationService notifications, ForumMapper mapper) {
        this.repository = repository;
        this.accounts = accounts;
        this.points = points;
        this.notifications = notifications;
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

    private String searchKeyword(String keyword) {
        String value = keyword == null ? "" : keyword.trim();
        check(value.length() <= 60, "搜索关键词不能超过 60 个字");
        return value.isEmpty() ? null : value;
    }

    private int pageOffset(int page) {
        check(page >= 1 && page <= 10000, "页码无效");
        return (page - 1) * 20;
    }

    private Map<String, Object> page(List<Map<String, Object>> rows) {
        boolean hasNext = rows.size() > 20;
        return Map.of("items", hasNext ? rows.subList(0, 20) : rows, "hasNext", hasNext);
    }

    public Map<String, Object> reputation(Authentication auth) {
        return Map.of("score", accounts.current(auth).get("reputation"), "logs", repository.reputation(id(auth)));
    }

    public List<Map<String, Object>> appealTargets(Authentication auth) {
        return repository.appealTargets(id(auth));
    }

    @Transactional
    public void appeal(Authentication auth, Map<String, Object> input) {
        String type = text(input, "targetType", 20), reason = text(input, "reason", 2000);
        long target = number(input, "targetId");
        check(Set.of("post", "reply", "user", "report", "message").contains(type) && reason.length() >= 10, "申诉内容无效");
        if ("post".equals(type)) {
            Map<String, Object> post = required(mapper.post(target));
            check(number(post, "author_id") == id(auth) && Set.of("hidden", "deleted").contains(post.get("status")) && repository.postModeration(target) != null, "只能申诉被处理的本人内容");
        }
        if ("reply".equals(type)) {
            Map<String, Object> reply = required(mapper.reply(target));
            check(number(reply, "author_id") == id(auth) && "deleted".equals(reply.get("status")) && repository.replyModeration(target) != null, "只能申诉被处理的本人内容");
        }
        if ("user".equals(type))
            check(target == id(auth) && !"active".equals(accounts.current(auth).get("status")), "只能申诉被处理的本人账号");
        if ("report".equals(type)) {
            Map<String, Object> report = required(mapper.reportById(target));
            check("resolved".equals(report.get("status")), "举报尚未处理");
            String targetType = String.valueOf(report.get("target_type"));
            long targetId = number(report, "target_id");
            check("user".equals(targetType) ? targetId == id(auth) : "post".equals(targetType) ? number(required(mapper.post(targetId)), "author_id") == id(auth) : "reply".equals(targetType) ? number(required(mapper.reply(targetId)), "author_id") == id(auth) : false, "不能申诉该举报");
        }
        if ("message".equals(type)) {
            Map<String, Object> message = required(repository.messageForAppeal(target));
            check(number(message, "sender_id") == id(auth) && "[已删除]".equals(message.get("content")), "只能申诉被处理的本人消息");
        }
        check(repository.pendingAppeal(id(auth), type, target) == null, "该对象已有待处理申诉");
        repository.createAppeal(id(auth), type, target, reason);
    }

    public List<Map<String, Object>> appeals(Authentication auth) {
        return repository.appeals(id(auth));
    }

    public Map<String, Object> adminAppeals(Authentication auth, String keyword, int page) {
        accounts.admin(auth);
        String name = searchKeyword(keyword);
        return page(repository.adminAppeals(name, pageOffset(page)));
    }

    @Transactional
    public void handleAppeal(Authentication auth, long appealId, Map<String, Object> input) {
        accounts.admin(auth);
        String status = text(input, "status", 20), note = text(input, "note", 500);
        check(Set.of("resolved", "rejected").contains(status) && !note.isBlank(), "处理结果及备注不能为空");
        Map<String, Object> appeal = required(repository.appeal(appealId));
        check(repository.handleAppeal(status, note, id(auth), appealId) == 1, "申诉已处理");
        boolean restoreAccount = "resolved".equals(status) && "user".equals(appeal.get("target_type"));
        if (restoreAccount)
            repository.restoreAccount(number(appeal, "target_id"));
        String message = "申诉已" + (restoreAccount ? "通过，账号已恢复" : "resolved".equals(status) ? "处理" : "驳回") + "：" + note;
        notifications.notifyUser(number(appeal, "user_id"), "appeal", message.substring(0, Math.min(255, message.length())), null);
        mapper.adminLog(id(auth), "appeal_" + status, "appeal", appealId, note);
    }

    public Map<String, Object> adminReputation(Authentication auth, String keyword, int page) {
        accounts.admin(auth);
        String name = searchKeyword(keyword);
        return page(repository.adminReputation(name, pageOffset(page)));
    }

    @Transactional
    public void adjustReputation(Authentication auth, long userId, Map<String, Object> input) {
        accounts.admin(auth);
        required(mapper.user(userId));
        long amount = number(input, "amount");
        String reason = text(input, "reason", 255);
        check(amount >= -100 && amount <= 100 && amount != 0 && reason.length() >= 3, "调整范围或理由无效");
        points.changeReputation(userId, id(auth), (int) amount, reason);
        mapper.adminLog(id(auth), "reputation", "user", userId, reason);
    }
}
