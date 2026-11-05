package com.studentforum.service;

import com.studentforum.mapper.ForumMapper;


import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import com.studentforum.repository.SocialRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SocialService {
    private final SocialRepository repository;
    private final AccountAccessService accounts;
    private final NotificationService notifications;
    private final ForumMapper mapper;

    public SocialService(SocialRepository repository, AccountAccessService accounts, NotificationService notifications, ForumMapper mapper) {
        this.repository = repository;
        this.accounts = accounts;
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
        Object value = input.get(key);
        if (!(value instanceof Number)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "参数无效");
        return ((Number) value).longValue();
    }

    private String text(Map<String, Object> input, String key, int max) {
        Object value = input.get(key);
        String result = value instanceof String ? ((String) value).trim() : "";
        if (result.length() > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内容过长");
        return result;
    }

    private void check(boolean condition, String message) {
        if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    @Transactional
    public void follow(Authentication auth, long userId) {
        long me = id(auth);
        check(me != userId, "不能关注自己");
        required(mapper.publicUser(userId));
        if (repository.follow(me, userId) > 0)
            notifications.notifyUser(userId, "follow", "有人关注了你", null);
    }

    @Transactional
    public void unfollow(Authentication auth, long userId) {
        long me = id(auth);
        if (repository.unfollow(me, userId) > 0 && Boolean.TRUE.equals(required(repository.notificationPreferences(userId)).get("notify_unfollow")))
            notifications.notifyUser(userId, "unfollow", "有人取消关注了你", null);
    }

    public List<Map<String, Object>> following(Authentication auth) {
        return repository.following(id(auth));
    }

    public List<Map<String, Object>> followers(Authentication auth) {
        return repository.followers(id(auth));
    }

    public Map<String, Object> social(Authentication auth, long userId) {
        required(mapper.publicUser(userId));
        long me = auth == null || "anonymousUser".equals(auth.getPrincipal()) ? -1 : id(auth);
        return required(repository.socialProfile(me, userId));
    }

    public List<Map<String, Object>> feed(Authentication auth) {
        return repository.feed(id(auth));
    }

    public void followTag(Authentication auth, long tagId) {
        required(mapper.tag(tagId));
        repository.followTag(id(auth), tagId);
    }

    public void unfollowTag(Authentication auth, long tagId) {
        repository.unfollowTag(id(auth), tagId);
    }

    public List<Map<String, Object>> followingTags(Authentication auth) {
        return repository.followedTags(id(auth));
    }

    public void settings(Authentication auth, Map<String, Object> input) {
        String privacy = text(input, "messagePrivacy", 20);
        check(Set.of("everyone", "following", "closed").contains(privacy), "私信隐私设置无效");
        repository.updateSettings(privacy, Boolean.TRUE.equals(input.get("notifyUnfollow")), !Boolean.FALSE.equals(input.get("notifyMention")), id(auth));
    }

    public void block(Authentication auth, long userId) {
        check(id(auth) != userId, "不能拉黑自己");
        required(mapper.publicUser(userId));
        repository.block(id(auth), userId);
    }

    public void unblock(Authentication auth, long userId) {
        repository.unblock(id(auth), userId);
    }

    public List<Map<String, Object>> conversations(Authentication auth) {
        return repository.conversations(id(auth));
    }

    public List<Map<String, Object>> messageRecipients(Authentication auth, String keyword) {
        String name = keyword.trim();
        check(name.length() >= 1 && name.length() <= 40, "请输入 1 至 40 个字的用户名");
        long me = id(auth);
        return repository.messageRecipients(me, name);
    }

    @Transactional
    public Map<String, Object> conversation(Authentication auth, Map<String, Object> input) {
        accounts.writable(auth);
        long me = id(auth), other = number(input, "userId");
        check(me != other, "不能给自己发私信");
        Map<String, Object> user = required(mapper.user(other));
        check("active".equals(user.get("status")), "对方暂不能接收私信");
        check(repository.blocksBetween(me, other) == null, "双方存在拉黑关系");
        check(!"closed".equals(user.get("message_privacy")), "对方关闭了私信");
        if ("following".equals(user.get("message_privacy")))
            check(repository.followingRelation(other, me) != null, "对方只接收关注者私信");
        long first = Math.min(me, other), second = Math.max(me, other);
        repository.createConversation(first, second);
        return required(repository.conversationByUsers(first, second));
    }

    private Map<String, Object> member(Authentication auth, long conversationId) {
        Map<String, Object> conversation = required(repository.conversation(conversationId));
        if (number(conversation, "user_a_id") != id(auth) && number(conversation, "user_b_id") != id(auth))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return conversation;
    }

    @Transactional
    public List<Map<String, Object>> messages(Authentication auth, long conversationId) {
        member(auth, conversationId);
        repository.readMessages(conversationId, id(auth));
        List<Map<String, Object>> items = repository.messages(conversationId);
        java.util.Collections.reverse(items);
        return items;
    }

    @Transactional
    public void message(Authentication auth, long conversationId, Map<String, Object> input) {
        Map<String, Object> conversation = member(auth, conversationId);
        accounts.writable(auth);
        long other = number(conversation, "user_a_id") == id(auth) ? number(conversation, "user_b_id") : number(conversation, "user_a_id");
        check(repository.blocksBetween(id(auth), other) == null, "双方存在拉黑关系");
        Map<String, Object> recipient = required(mapper.user(other));
        check("active".equals(recipient.get("status")) && !"closed".equals(recipient.get("message_privacy")), "对方暂不能接收私信");
        if ("following".equals(recipient.get("message_privacy")))
            check(repository.followingRelation(other, id(auth)) != null, "对方只接收关注者私信");
        if (repository.dailyMessageCount(id(auth)) >= 100)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "今日私信次数已达上限");
        String type = text(input, "type", 10), content = text(input, "content", 2000);
        check(Set.of("text", "image").contains(type), "消息类型无效");
        if ("image".equals(type)) {
            check(!content.isBlank(), "请选择图片");
            content = com.studentforum.model.ContentRules.image(content);
        } else com.studentforum.model.ContentRules.text(content, 1, 2000);
        long messageId = repository.insertMessage(conversationId, id(auth), content, type);
        repository.touchConversation(conversationId);
        if ("text".equals(type))
            notifications.mentions(id(auth), content, "message", messageId, null);
    }

    public void mute(Authentication auth, long conversationId, Map<String, Object> input) {
        member(auth, conversationId);
        repository.muteConversation(conversationId, id(auth), Boolean.TRUE.equals(input.get("muted")));
    }

    @Transactional
    public void reportMessage(Authentication auth, long messageId, Map<String, Object> input) {
        Map<String, Object> message = required(repository.reportableMessage(messageId, id(auth)));
        check(number(message, "sender_id") != id(auth), "不能举报自己的消息");
        if (mapper.dailyReports(id(auth)) >= 5)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "今日举报次数已达上限");
        String reason = text(input, "reason", 40);
        check(Set.of("广告垃圾", "人身攻击", "抄袭侵权", "色情暴力", "政治敏感", "其他").contains(reason), "举报原因无效");
        mapper.report(id(auth), "message", messageId, reason, text(input, "description", 500));
    }
}
