package com.studentforum.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WikiRepository {
    private final JdbcTemplate db;

    public WikiRepository(JdbcTemplate db) {
        this.db = db;
    }

    public Map<String, Object> enabledBoard(long boardId) {
        return db.queryForList("SELECT * FROM boards WHERE id=? AND status='enabled'", boardId).stream().findFirst().orElse(null);
    }

    public List<Map<String, Object>> pages(long boardId, boolean edit, String q) {
        return db.queryForList("SELECT p.*,u.username FROM wiki_pages p JOIN users u ON u.id=p.updated_by WHERE p.board_id=? AND (p.status='published' OR ?) AND (?='' OR LOCATE(?,p.title)>0 OR LOCATE(?,p.content)>0 OR LOCATE(?,p.tags)>0) ORDER BY p.id", boardId, edit, q, q, q, q);
    }

    public Map<String, Object> enabledBoardId(long boardId) {
        return db.queryForList("SELECT id FROM boards WHERE id=? AND status='enabled'", boardId).stream().findFirst().orElse(null);
    }

    public Map<String, Object> sameTitle(long boardId, String title) {
        return db.queryForList("SELECT id FROM wiki_pages WHERE board_id=? AND title=?", boardId, title).stream().findFirst().orElse(null);
    }

    public long insertPage(long boardId, String title, String content, String status, long user, String tags, Long post) {
        var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
        db.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO wiki_pages(board_id,title,content,status,updated_by,tags,post_id) VALUES(?,?,?,?,?,?,?)", java.sql.Statement.RETURN_GENERATED_KEYS);
            Object[] fields = {boardId, title, content, status, user, tags, post};
            for (int i = 0; i < fields.length; i++) statement.setObject(i + 1, fields[i]);
            return statement;
        }, key);
        return java.util.Objects.requireNonNull(key.getKey()).longValue();
    }

    public Map<String, Object> lockPage(long pageId) {
        return db.queryForList("SELECT * FROM wiki_pages WHERE id=? FOR UPDATE", pageId).stream().findFirst().orElse(null);
    }

    public Map<String, Object> latestRevision(long pageId) {
        return db.queryForList("SELECT id FROM wiki_revisions WHERE page_id=? ORDER BY id DESC LIMIT 1", pageId).stream().findFirst().orElse(null);
    }

    public Map<String, Object> otherPageWithTitle(long boardId, String title, long pageId) {
        return db.queryForList("SELECT id FROM wiki_pages WHERE board_id=? AND title=? AND id<>?", boardId, title, pageId).stream().findFirst().orElse(null);
    }

    public int updatePage(String title, String content, String status, long user, String tags, Long post, long pageId) {
        return db.update("UPDATE wiki_pages SET title=?,content=?,status=?,updated_by=?,tags=?,post_id=? WHERE id=?", title, content, status, user, tags, post, pageId);
    }

    public Map<String, Object> publishedPost(long id) {
        return db.queryForList("SELECT id FROM posts WHERE id=? AND status='published'", id).stream().findFirst().orElse(null);
    }

    public int insertRevision(long id, long user, String title, String content, String tags, Long post) {
        return db.update("INSERT INTO wiki_revisions(page_id,editor_id,title,content,tags,post_id) VALUES(?,?,?,?,?,?)", id, user, title, content, tags, post);
    }

    public Map<String, Object> findPage(long pageId) {
        return db.queryForList("SELECT * FROM wiki_pages WHERE id=?", pageId).stream().findFirst().orElse(null);
    }

    public List<Map<String, Object>> history(long pageId) {
        return db.queryForList("SELECT r.*,u.username FROM wiki_revisions r JOIN users u ON u.id=r.editor_id WHERE r.page_id=? ORDER BY r.id DESC LIMIT 100", pageId);
    }

    public int setStatus(String status, long user, long id) {
        return db.update("UPDATE wiki_pages SET status=?,updated_by=? WHERE id=?", status, user, id);
    }

    public List<Map<String, Object>> adminPages(boolean administrator, long scope, int offset) {
        String where = administrator ? "?=0" : "p.board_id=?";
        return db.queryForList("SELECT p.*,b.name AS board_name,u.username FROM wiki_pages p JOIN boards b ON b.id=p.board_id JOIN users u ON u.id=p.updated_by WHERE "+where+" ORDER BY p.status='draft' DESC,p.updated_at DESC LIMIT 21 OFFSET ?", scope, offset);
    }
}
