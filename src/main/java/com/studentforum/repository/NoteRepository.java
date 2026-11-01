package com.studentforum.repository;

import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class NoteRepository {
    private final JdbcTemplate db;

    public NoteRepository(JdbcTemplate db) {
        this.db = db;
    }

    public List<Map<String, Object>> search(boolean mine, long userId, String query,
                                            String subject, int offset) {
        String visibility = mine ? "n.user_id=?" : "n.status='published' AND ?>=0";
        String sql = "SELECT n.*,u.username FROM notes n JOIN users u ON u.id=n.user_id WHERE "
                + visibility
                + " AND (?='' OR n.subject=?) AND (?='' OR LOCATE(?,n.title)>0 OR LOCATE(?,n.tags)>0 OR LOCATE(?,n.content)>0)"
                + " ORDER BY n.updated_at DESC,n.id DESC LIMIT 21 OFFSET ?";
        return db.queryForList(sql, userId, subject, subject, query, query, query, query, offset);
    }

    public long insert(long userId, String title, String content, String subject,
                       String tags, String status) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        db.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO notes(user_id,title,content,subject,tags,status) VALUES(?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            Object[] fields = {userId, title, content, subject, tags, status};
            for (int i = 0; i < fields.length; i++) statement.setObject(i + 1, fields[i]);
            return statement;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    public boolean lock(long id) {
        return !db.queryForList("SELECT id FROM notes WHERE id=? FOR UPDATE", id).isEmpty();
    }

    public void update(long id, String title, String content, String subject,
                       String tags, String status) {
        db.update("UPDATE notes SET title=?,content=?,subject=?,tags=?,status=? WHERE id=?",
                title, content, subject, tags, status, id);
    }

    public void addRevision(long noteId, long userId, String title, String content) {
        db.update("INSERT INTO note_revisions(note_id,user_id,title,content) VALUES(?,?,?,?)",
                noteId, userId, title, content);
    }

    public List<Map<String, Object>> revisions(long noteId) {
        return db.queryForList("SELECT * FROM note_revisions WHERE note_id=? ORDER BY id DESC LIMIT 100", noteId);
    }
}
