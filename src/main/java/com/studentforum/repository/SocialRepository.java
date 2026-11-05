package com.studentforum.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SocialRepository {
    private final JdbcTemplate db;

    public SocialRepository(JdbcTemplate db) {
        this.db = db;
    }

    public int follow(long me, long userId) {
        return db.update("INSERT IGNORE INTO follows(follower_id,followee_id) VALUES(?,?)", me, userId);
    }

    public int unfollow(long me, long userId) {
        return db.update("DELETE FROM follows WHERE follower_id=? AND followee_id=?", me, userId);
    }

    public List<Map<String, Object>> following(long user) {
        return db.queryForList("SELECT u.id,u.username FROM follows f JOIN users u ON u.id=f.followee_id WHERE f.follower_id=? ORDER BY f.created_at DESC LIMIT 100", user);
    }

    public List<Map<String, Object>> followers(long user) {
        return db.queryForList("SELECT u.id,u.username FROM follows f JOIN users u ON u.id=f.follower_id WHERE f.followee_id=? ORDER BY f.created_at DESC LIMIT 100", user);
    }

    public List<Map<String, Object>> feed(long user) {
        return db.queryForList("SELECT 'post' kind,p.id post_id,p.title summary,u.username,p.created_at FROM posts p JOIN follows f ON f.followee_id=p.author_id JOIN users u ON u.id=p.author_id WHERE f.follower_id=? AND p.status='published' UNION ALL SELECT 'tag',p.id,p.title,u.username,p.created_at FROM posts p JOIN post_tags pt ON pt.post_id=p.id JOIN followed_tags ft ON ft.tag_id=pt.tag_id AND ft.user_id=? JOIN users u ON u.id=p.author_id WHERE p.status='published' UNION ALL SELECT 'reply',p.id,r.content,u.username,r.created_at FROM replies r JOIN posts p ON p.id=r.post_id JOIN follows f ON f.followee_id=r.author_id JOIN users u ON u.id=r.author_id WHERE f.follower_id=? AND r.status='published' AND p.status='published' UNION ALL SELECT 'accepted',p.id,p.title,u.username,p.accepted_at AS created_at FROM replies r JOIN posts p ON p.accepted_reply_id=r.id JOIN follows f ON f.followee_id=p.author_id JOIN users u ON u.id=p.author_id WHERE f.follower_id=? AND p.status='published' UNION ALL SELECT 'featured',p.id,p.title,u.username,p.featured_at AS created_at FROM posts p JOIN follows f ON f.followee_id=p.author_id JOIN users u ON u.id=p.author_id WHERE f.follower_id=? AND p.is_featured=TRUE AND p.status='published' ORDER BY created_at DESC LIMIT 100", user, user, user, user, user);
    }

    public int followTag(long user, long tagId) {
        return db.update("INSERT IGNORE INTO followed_tags(user_id,tag_id) VALUES(?,?)", user, tagId);
    }

    public int unfollowTag(long user, long tagId) {
        return db.update("DELETE FROM followed_tags WHERE user_id=? AND tag_id=?", user, tagId);
    }

    public List<Map<String, Object>> followedTags(long user) {
        return db.queryForList("SELECT t.id,t.name FROM followed_tags f JOIN tags t ON t.id=f.tag_id WHERE f.user_id=? AND t.status='enabled' ORDER BY t.name", user);
    }

    public int updateSettings(String privacy, boolean notifyUnfollow, boolean notifyMention, long user) {
        return db.update("UPDATE users SET message_privacy=?,notify_unfollow=?,notify_mention=? WHERE id=?", privacy, notifyUnfollow, notifyMention, user);
    }

    public int block(long user, long blockedId) {
        return db.update("INSERT IGNORE INTO user_blocks(user_id,blocked_id) VALUES(?,?)", user, blockedId);
    }

    public int unblock(long user, long blockedId) {
        return db.update("DELETE FROM user_blocks WHERE user_id=? AND blocked_id=?", user, blockedId);
    }

    public List<Map<String, Object>> conversations(long user) {
        return db.queryForList("SELECT c.id,c.updated_at,u.id other_id,u.username,COALESCE(s.muted,FALSE) muted,(SELECT content FROM messages WHERE conversation_id=c.id ORDER BY id DESC LIMIT 1) last_message,(SELECT COUNT(*) FROM messages WHERE conversation_id=c.id AND sender_id<>? AND is_read=FALSE) unread FROM conversations c JOIN users u ON u.id=IF(c.user_a_id=?,c.user_b_id,c.user_a_id) LEFT JOIN conversation_settings s ON s.conversation_id=c.id AND s.user_id=? WHERE c.user_a_id=? OR c.user_b_id=? ORDER BY c.updated_at DESC LIMIT 100", user, user, user, user, user);
    }

    public List<Map<String, Object>> messageRecipients(long user, String name) {
        return db.queryForList("""
            SELECT u.id,u.username,
              CASE WHEN u.status<>'active' THEN '账号暂不能接收私信'
                   WHEN EXISTS (SELECT 1 FROM user_blocks b WHERE (b.user_id=? AND b.blocked_id=u.id) OR (b.user_id=u.id AND b.blocked_id=?)) THEN '双方存在拉黑关系'
                   WHEN u.message_privacy='closed' THEN '对方关闭了私信'
                   WHEN u.message_privacy='following' AND NOT EXISTS (SELECT 1 FROM follows f WHERE f.follower_id=u.id AND f.followee_id=?) THEN '对方仅接收关注者私信'
                   ELSE NULL END AS unavailable_reason
            FROM users u
            WHERE u.id<>? AND LOCATE(LOWER(?),LOWER(u.username))>0
            ORDER BY CASE WHEN LOWER(u.username)=LOWER(?) THEN 0 WHEN LOCATE(LOWER(?),LOWER(u.username))=1 THEN 1 ELSE 2 END,u.username
            LIMIT 20
            """, user, user, user, user, name, name, name);
    }

    public int createConversation(long first, long second) {
        return db.update("INSERT IGNORE INTO conversations(user_a_id,user_b_id) VALUES(?,?)", first, second);
    }

    public int readMessages(long conversationId, long user) {
        return db.update("UPDATE messages SET is_read=TRUE WHERE conversation_id=? AND sender_id<>?", conversationId, user);
    }

    public List<Map<String, Object>> messages(long conversationId) {
        return db.queryForList("SELECT id,sender_id,content,type,is_read,created_at FROM messages WHERE conversation_id=? ORDER BY id DESC LIMIT 100", conversationId);
    }

    public Integer dailyMessageCount(long user) {
        return db.queryForObject("SELECT COUNT(*) FROM messages WHERE sender_id=? AND created_at>=NOW()-INTERVAL 1 DAY", Integer.class, user);
    }

    public long insertMessage(long conversationId, long user, String content, String type) {
        var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
        db.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO messages(conversation_id,sender_id,content,type) VALUES(?,?,?,?)", java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, conversationId);
            statement.setLong(2, user);
            statement.setString(3, content);
            statement.setString(4, type);
            return statement;
        }, key);
        return java.util.Objects.requireNonNull(key.getKey()).longValue();
    }

    public int touchConversation(long conversationId) {
        return db.update("UPDATE conversations SET updated_at=NOW() WHERE id=?", conversationId);
    }

    public int muteConversation(long conversationId, long user, boolean muted) {
        return db.update("INSERT INTO conversation_settings(conversation_id,user_id,muted) VALUES(?,?,?) ON DUPLICATE KEY UPDATE muted=VALUES(muted)", conversationId, user, muted);
    }

    public Map<String, Object> notificationPreferences(long userId) {
        return db.queryForList("SELECT notify_unfollow FROM users WHERE id=?", userId).stream().findFirst().orElse(null);
    }

    public Map<String, Object> socialProfile(long viewer, long userId) {
        return db.queryForList("SELECT (SELECT COUNT(*) FROM follows WHERE follower_id=u.id) following,(SELECT COUNT(*) FROM follows WHERE followee_id=u.id) followers,(SELECT COUNT(*) FROM favorites f JOIN posts p ON p.id=f.post_id WHERE p.author_id=u.id) likes,(SELECT COUNT(*) FROM replies WHERE author_id=u.id AND is_accepted=TRUE) accepted,(SELECT COUNT(*) FROM posts WHERE author_id=u.id AND is_featured=TRUE) featured,EXISTS(SELECT 1 FROM follows WHERE follower_id=? AND followee_id=u.id) followed FROM users u WHERE u.id=?", viewer, userId).stream().findFirst().orElse(null);
    }

    public Map<String, Object> blocksBetween(long user, long other) {
        return db.queryForList("SELECT 1 FROM user_blocks WHERE (user_id=? AND blocked_id=?) OR (user_id=? AND blocked_id=?)", user, other, other, user).stream().findFirst().orElse(null);
    }

    public Map<String, Object> followingRelation(long other, long user) {
        return db.queryForList("SELECT 1 FROM follows WHERE follower_id=? AND followee_id=?", other, user).stream().findFirst().orElse(null);
    }

    public Map<String, Object> conversationByUsers(long first, long second) {
        return db.queryForList("SELECT id FROM conversations WHERE user_a_id=? AND user_b_id=?", first, second).stream().findFirst().orElse(null);
    }

    public Map<String, Object> conversation(long conversationId) {
        return db.queryForList("SELECT * FROM conversations WHERE id=?", conversationId).stream().findFirst().orElse(null);
    }

    public Map<String, Object> reportableMessage(long messageId, long user) {
        return db.queryForList("SELECT m.* FROM messages m JOIN conversations c ON c.id=m.conversation_id WHERE m.id=? AND (c.user_a_id=? OR c.user_b_id=?)", messageId, user, user).stream().findFirst().orElse(null);
    }
}
