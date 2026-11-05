package com.studentforum.repository;

import com.studentforum.mapper.ForumMapper;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ForumRepository {
    private final JdbcTemplate db;
    private final ForumMapper mapper;

    public ForumRepository(JdbcTemplate db, ForumMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    public Map<String, Object> uploads(long postId) {
        return db.queryForMap("SELECT image_path,cover_path,attachment_path FROM posts WHERE id=?", postId);
    }

    public void deletePost(long postId) {
        // 回复版本和提及必须在回复行消失之前清理；积分与审计记录保留。
        db.update("DELETE FROM reply_revisions WHERE reply_id IN (SELECT id FROM replies WHERE post_id=?)", postId);
        db.update("DELETE FROM mentions WHERE (target_type='post' AND target_id=?) OR (target_type='reply' AND target_id IN (SELECT id FROM replies WHERE post_id=?))", postId, postId);
        db.update("DELETE FROM replies WHERE post_id=?", postId);
        db.update("DELETE FROM post_tags WHERE post_id=?", postId);
        db.update("DELETE FROM favorites WHERE post_id=?", postId);
        mapper.deletePost(postId);
        db.update("DELETE FROM post_revisions WHERE post_id=?", postId);
        db.update("DELETE FROM notifications WHERE post_id=?", postId);
        db.update("DELETE FROM vote_ballots WHERE vote_id IN (SELECT id FROM votes WHERE post_id=?)", postId);
        db.update("DELETE FROM vote_options WHERE vote_id IN (SELECT id FROM votes WHERE post_id=?)", postId);
        db.update("DELETE FROM votes WHERE post_id=?", postId);
        db.update("DELETE FROM bounties WHERE post_id=?", postId);
    }

    public void revision(long postId, long editor, String title, String content) {
        db.update("INSERT INTO post_revisions(post_id,editor_id,title,content) VALUES(?,?,?,?)", postId, editor, title, content);
    }

    public void attachment(long postId, String path) {
        db.update("UPDATE posts SET attachment_path=? WHERE id=?", path, postId);
    }

    public void cover(long postId, String path) {
        db.update("UPDATE posts SET cover_path=? WHERE id=?", path, postId);
    }

    public void quote(long replyId, long quoteId) {
        db.update("UPDATE replies SET quote_reply_id=? WHERE id=?", quoteId, replyId);
    }

    public void replyRevision(long replyId, long editor, String content) {
        db.update("INSERT INTO reply_revisions(reply_id,editor_id,content) VALUES(?,?,?)", replyId, editor, content);
    }

    public List<Map<String, Object>> replies(long postId, String sort) {
        String order = switch (sort) {
            case "latest" -> "r.created_at DESC";
            case "hot" -> "u.points DESC,r.created_at DESC";
            default -> "r.created_at ASC";
        };
        return db.queryForList("SELECT r.*,u.username,u.points AS author_points,u.role AS author_role,mb.name AS author_board_name,q.content AS quote_content FROM replies r JOIN users u ON u.id=r.author_id LEFT JOIN boards mb ON mb.id=u.moderator_board_id LEFT JOIN replies q ON q.id=r.quote_reply_id AND q.status='published' WHERE r.post_id=? AND r.status='published' ORDER BY r.is_accepted DESC," + order + " LIMIT 200", postId);
    }

    public Map<String, Object> openBounty(long postId) {
        return db.queryForList("SELECT amount FROM bounties WHERE post_id=? AND status='open'", postId).stream().findFirst().orElse(null);
    }

    public void awardBounty(long postId, long recipient) {
        db.update("UPDATE bounties SET status='awarded',recipient_id=? WHERE post_id=?", recipient, postId);
    }

    public void deleteMessage(long id) {
        db.update("UPDATE messages SET content='[已删除]',type='text' WHERE id=?", id);
    }

    public void rejectResource(long id) {
        db.update("UPDATE resources SET status='rejected' WHERE id=?", id);
    }

    public Map<String, Object> resourceAuthor(long id) {
        return db.queryForList("SELECT uploader_id FROM resources WHERE id=?", id).stream().findFirst().orElse(null);
    }

    public Map<String, Object> messageAuthor(long id) {
        return db.queryForList("SELECT sender_id FROM messages WHERE id=?", id).stream().findFirst().orElse(null);
    }
}
