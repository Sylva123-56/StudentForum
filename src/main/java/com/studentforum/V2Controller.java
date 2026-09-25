package com.studentforum;

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
public class V2Controller {
    private final JdbcTemplate db;
    private final ForumService service;
    private final ForumMapper mapper;

    V2Controller(JdbcTemplate db, ForumService service, ForumMapper mapper) {
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

    @PostMapping("/follows/{userId}")
    @Transactional
    void follow(Authentication auth, @PathVariable long userId) {
        long me = id(auth);
        check(me != userId, "不能关注自己");
        service.require(mapper.publicUser(userId));
        if (db.update("INSERT IGNORE INTO follows(follower_id,followee_id) VALUES(?,?)", me, userId) > 0)
            mapper.notifyUser(userId, "follow", "有人关注了你", null);
    }

    @DeleteMapping("/follows/{userId}")
    @Transactional
    void unfollow(Authentication auth, @PathVariable long userId) {
        long me = id(auth);
        if (db.update("DELETE FROM follows WHERE follower_id=? AND followee_id=?", me, userId) > 0 && Boolean.TRUE.equals(required("SELECT notify_unfollow FROM users WHERE id=?", userId).get("notify_unfollow")))
            mapper.notifyUser(userId, "unfollow", "有人取消关注了你", null);
    }

    @GetMapping("/me/following")
    List<Map<String, Object>> following(Authentication auth) {
        return db.queryForList("SELECT u.id,u.username FROM follows f JOIN users u ON u.id=f.followee_id WHERE f.follower_id=? ORDER BY f.created_at DESC LIMIT 100", id(auth));
    }

    @GetMapping("/me/followers")
    List<Map<String, Object>> followers(Authentication auth) {
        return db.queryForList("SELECT u.id,u.username FROM follows f JOIN users u ON u.id=f.follower_id WHERE f.followee_id=? ORDER BY f.created_at DESC LIMIT 100", id(auth));
    }

    @GetMapping("/users/{userId}/social")
    Map<String, Object> social(Authentication auth, @PathVariable long userId) {
        service.require(mapper.publicUser(userId));
        long me = auth == null || "anonymousUser".equals(auth.getPrincipal()) ? -1 : id(auth);
        return required("SELECT (SELECT COUNT(*) FROM follows WHERE follower_id=u.id) following,(SELECT COUNT(*) FROM follows WHERE followee_id=u.id) followers,(SELECT COUNT(*) FROM favorites f JOIN posts p ON p.id=f.post_id WHERE p.author_id=u.id) likes,(SELECT COUNT(*) FROM replies WHERE author_id=u.id AND is_accepted=TRUE) accepted,(SELECT COUNT(*) FROM posts WHERE author_id=u.id AND is_featured=TRUE) featured,EXISTS(SELECT 1 FROM follows WHERE follower_id=? AND followee_id=u.id) followed FROM users u WHERE u.id=?", me, userId);
    }

    @GetMapping("/feed/following")
    List<Map<String, Object>> feed(Authentication auth) {
        return db.queryForList("SELECT 'post' kind,p.id post_id,p.title summary,u.username,p.created_at FROM posts p JOIN follows f ON f.followee_id=p.author_id JOIN users u ON u.id=p.author_id WHERE f.follower_id=? AND p.status='published' UNION ALL SELECT 'tag',p.id,p.title,u.username,p.created_at FROM posts p JOIN post_tags pt ON pt.post_id=p.id JOIN followed_tags ft ON ft.tag_id=pt.tag_id AND ft.user_id=? JOIN users u ON u.id=p.author_id WHERE p.status='published' UNION ALL SELECT 'reply',p.id,r.content,u.username,r.created_at FROM replies r JOIN posts p ON p.id=r.post_id JOIN follows f ON f.followee_id=r.author_id JOIN users u ON u.id=r.author_id WHERE f.follower_id=? AND r.status='published' AND p.status='published' UNION ALL SELECT 'accepted',p.id,p.title,u.username,p.accepted_at AS created_at FROM replies r JOIN posts p ON p.accepted_reply_id=r.id JOIN follows f ON f.followee_id=p.author_id JOIN users u ON u.id=p.author_id WHERE f.follower_id=? AND p.status='published' UNION ALL SELECT 'featured',p.id,p.title,u.username,p.featured_at AS created_at FROM posts p JOIN follows f ON f.followee_id=p.author_id JOIN users u ON u.id=p.author_id WHERE f.follower_id=? AND p.is_featured=TRUE AND p.status='published' ORDER BY created_at DESC LIMIT 100", id(auth), id(auth), id(auth), id(auth), id(auth));
    }

    @PostMapping("/me/tags/{tagId}/follow")
    void followTag(Authentication auth, @PathVariable long tagId) {
        service.require(mapper.tag(tagId));
        db.update("INSERT IGNORE INTO followed_tags(user_id,tag_id) VALUES(?,?)", id(auth), tagId);
    }

    @DeleteMapping("/me/tags/{tagId}/follow")
    void unfollowTag(Authentication auth, @PathVariable long tagId) {
        db.update("DELETE FROM followed_tags WHERE user_id=? AND tag_id=?", id(auth), tagId);
    }

    @GetMapping("/me/tags/following")
    List<Map<String, Object>> followingTags(Authentication auth) {
        return db.queryForList("SELECT t.id,t.name FROM followed_tags f JOIN tags t ON t.id=f.tag_id WHERE f.user_id=? AND t.status='enabled' ORDER BY t.name", id(auth));
    }

    @PutMapping("/me/notifications/settings")
    void settings(Authentication auth, @RequestBody Map<String, Object> input) {
        String privacy = text(input, "messagePrivacy", 20);
        check(Set.of("everyone", "following", "closed").contains(privacy), "私信隐私设置无效");
        db.update("UPDATE users SET message_privacy=?,notify_unfollow=?,notify_mention=? WHERE id=?", privacy, Boolean.TRUE.equals(input.get("notifyUnfollow")), !Boolean.FALSE.equals(input.get("notifyMention")), id(auth));
    }

    @PostMapping("/users/{userId}/block")
    void block(Authentication auth, @PathVariable long userId) {
        check(id(auth) != userId, "不能拉黑自己");
        service.require(mapper.publicUser(userId));
        db.update("INSERT IGNORE INTO user_blocks(user_id,blocked_id) VALUES(?,?)", id(auth), userId);
    }

    @DeleteMapping("/users/{userId}/block")
    void unblock(Authentication auth, @PathVariable long userId) {
        db.update("DELETE FROM user_blocks WHERE user_id=? AND blocked_id=?", id(auth), userId);
    }

    @GetMapping("/conversations")
    List<Map<String, Object>> conversations(Authentication auth) {
        return db.queryForList("SELECT c.id,c.updated_at,u.id other_id,u.username,COALESCE(s.muted,FALSE) muted,(SELECT content FROM messages WHERE conversation_id=c.id ORDER BY id DESC LIMIT 1) last_message,(SELECT COUNT(*) FROM messages WHERE conversation_id=c.id AND sender_id<>? AND is_read=FALSE) unread FROM conversations c JOIN users u ON u.id=IF(c.user_a_id=?,c.user_b_id,c.user_a_id) LEFT JOIN conversation_settings s ON s.conversation_id=c.id AND s.user_id=? WHERE c.user_a_id=? OR c.user_b_id=? ORDER BY c.updated_at DESC LIMIT 100", id(auth), id(auth), id(auth), id(auth), id(auth));
    }

    @PostMapping("/conversations")
    @Transactional
    Map<String, Object> conversation(Authentication auth, @RequestBody Map<String, Object> input) {
        service.writable(auth);
        long me = id(auth), other = number(input, "userId");
        check(me != other, "不能给自己发私信");
        Map<String, Object> user = service.require(mapper.user(other));
        check("active".equals(user.get("status")), "对方暂不能接收私信");
        check(row("SELECT 1 FROM user_blocks WHERE (user_id=? AND blocked_id=?) OR (user_id=? AND blocked_id=?)", me, other, other, me) == null, "双方存在拉黑关系");
        check(!"closed".equals(user.get("message_privacy")), "对方关闭了私信");
        if ("following".equals(user.get("message_privacy")))
            check(row("SELECT 1 FROM follows WHERE follower_id=? AND followee_id=?", other, me) != null, "对方只接收关注者私信");
        long first = Math.min(me, other), second = Math.max(me, other);
        db.update("INSERT IGNORE INTO conversations(user_a_id,user_b_id) VALUES(?,?)", first, second);
        return required("SELECT id FROM conversations WHERE user_a_id=? AND user_b_id=?", first, second);
    }

    private Map<String, Object> member(Authentication auth, long conversationId) {
        Map<String, Object> conversation = required("SELECT * FROM conversations WHERE id=?", conversationId);
        if (service.number(conversation, "user_a_id") != id(auth) && service.number(conversation, "user_b_id") != id(auth))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return conversation;
    }

    @GetMapping("/conversations/{conversationId}/messages")
    @Transactional
    List<Map<String, Object>> messages(Authentication auth, @PathVariable long conversationId) {
        member(auth, conversationId);
        db.update("UPDATE messages SET is_read=TRUE WHERE conversation_id=? AND sender_id<>?", conversationId, id(auth));
        List<Map<String, Object>> items = db.queryForList("SELECT id,sender_id,content,type,is_read,created_at FROM messages WHERE conversation_id=? ORDER BY id DESC LIMIT 100", conversationId);
        java.util.Collections.reverse(items);
        return items;
    }

    @PostMapping("/conversations/{conversationId}/messages")
    @Transactional
    void message(Authentication auth, @PathVariable long conversationId, @RequestBody Map<String, Object> input) {
        Map<String, Object> conversation = member(auth, conversationId);
        service.writable(auth);
        long other = service.number(conversation, "user_a_id") == id(auth) ? service.number(conversation, "user_b_id") : service.number(conversation, "user_a_id");
        check(row("SELECT 1 FROM user_blocks WHERE (user_id=? AND blocked_id=?) OR (user_id=? AND blocked_id=?)", id(auth), other, other, id(auth)) == null, "双方存在拉黑关系");
        Map<String, Object> recipient = service.require(mapper.user(other));
        check("active".equals(recipient.get("status")) && !"closed".equals(recipient.get("message_privacy")), "对方暂不能接收私信");
        if ("following".equals(recipient.get("message_privacy")))
            check(row("SELECT 1 FROM follows WHERE follower_id=? AND followee_id=?", other, id(auth)) != null, "对方只接收关注者私信");
        if (db.queryForObject("SELECT COUNT(*) FROM messages WHERE sender_id=? AND created_at>=NOW()-INTERVAL 1 DAY", Integer.class, id(auth)) >= 100)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "今日私信次数已达上限");
        String type = text(input, "type", 10), content = text(input, "content", 2000);
        check(Set.of("text", "image").contains(type), "消息类型无效");
        if ("image".equals(type)) {
            check(!content.isBlank(), "请选择图片");
            content = service.image(content);
        } else service.text(content, 1, 2000);
        db.update("INSERT INTO messages(conversation_id,sender_id,content,type) VALUES(?,?,?,?)", conversationId, id(auth), content, type);
        db.update("UPDATE conversations SET updated_at=NOW() WHERE id=?", conversationId);
        if ("text".equals(type))
            service.mentions(id(auth), content, "message", db.queryForObject("SELECT LAST_INSERT_ID()", Long.class), null);
    }

    @PatchMapping("/conversations/{conversationId}/mute")
    void mute(Authentication auth, @PathVariable long conversationId, @RequestBody Map<String, Object> input) {
        member(auth, conversationId);
        db.update("INSERT INTO conversation_settings(conversation_id,user_id,muted) VALUES(?,?,?) ON DUPLICATE KEY UPDATE muted=VALUES(muted)", conversationId, id(auth), Boolean.TRUE.equals(input.get("muted")));
    }

    @PostMapping("/messages/{messageId}/report")
    @Transactional
    void reportMessage(Authentication auth, @PathVariable long messageId, @RequestBody Map<String, Object> input) {
        Map<String, Object> message = required("SELECT m.* FROM messages m JOIN conversations c ON c.id=m.conversation_id WHERE m.id=? AND (c.user_a_id=? OR c.user_b_id=?)", messageId, id(auth), id(auth));
        check(service.number(message, "sender_id") != id(auth), "不能举报自己的消息");
        if (mapper.dailyReports(id(auth)) >= 5)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "今日举报次数已达上限");
        String reason = text(input, "reason", 40);
        check(Set.of("广告垃圾", "人身攻击", "抄袭侵权", "色情暴力", "政治敏感", "其他").contains(reason), "举报原因无效");
        mapper.report(id(auth), "message", messageId, reason, text(input, "description", 500));
    }
}
