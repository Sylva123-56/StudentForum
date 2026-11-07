package com.studentforum.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ResourceRepository {
    private final JdbcTemplate db;

    public ResourceRepository(JdbcTemplate db) { this.db=db; }

    public List<Map<String,Object>> search(String q, String subject, String grade, String textbook, String exam, boolean mine, boolean favorite, long user, int offset) {
        String sql="SELECT r.*,u.username,(SELECT COUNT(*) FROM resource_favorites f WHERE f.resource_id=r.id) AS favorite_count,(SELECT COUNT(*) FROM resource_votes v WHERE v.resource_id=r.id) AS like_count FROM resources r JOIN users u ON u.id=r.uploader_id WHERE "+(mine?"r.uploader_id=?":"r.status='published'");
        List<Object> args=new ArrayList<>(); if(mine)args.add(user);
        for(var entry:Map.of("subject",subject,"grade",grade,"textbook",textbook,"exam",exam).entrySet())if(!entry.getValue().isBlank()){ sql+=" AND r."+entry.getKey()+"=?"; args.add(entry.getValue().trim()); }
        if(!q.isBlank()){sql+=" AND (LOCATE(?,r.title)>0 OR LOCATE(?,r.description)>0 OR LOCATE(?,r.tags)>0)"; args.add(q.trim());args.add(q.trim());args.add(q.trim());}
        if(favorite){sql+=" AND EXISTS(SELECT 1 FROM resource_favorites f WHERE f.resource_id=r.id AND f.user_id=?)";args.add(user);}
        sql+=" ORDER BY r.id DESC LIMIT 21 OFFSET ?";args.add(offset); return db.queryForList(sql,args.toArray());
    }

    public Map<String,Object> lockUnusedUpload(long upload, long user) {
        return db.queryForList("SELECT * FROM resource_uploads WHERE id=? AND user_id=? AND resource_id IS NULL FOR UPDATE",upload,user).stream().findFirst().orElse(null);
    }

    public int attachFile(long id, Map<String,Object> f) {
        return db.update("INSERT INTO resource_files(resource_id,file_name,file_path,mime_type,size_bytes) VALUES(?,?,?,?,?)",id,f.get("file_name"),f.get("stored_name"),f.get("mime_type"),f.get("size_bytes"));
    }

    public int claimUpload(long id, long upload) {
        return db.update("UPDATE resource_uploads SET resource_id=? WHERE id=?",id,upload);
    }

    public List<Map<String,Object>> files(long id) {
        return db.queryForList("SELECT id,file_name,size_bytes,mime_type FROM resource_files WHERE resource_id=?",id);
    }

    public List<Map<String,Object>> comments(long id) {
        return db.queryForList("SELECT c.*,u.username FROM resource_comments c JOIN users u ON u.id=c.user_id WHERE c.resource_id=? ORDER BY c.id DESC LIMIT 100",id);
    }

    public Map<String,Object> favoriteBy(long id, long user) {
        return db.queryForList("SELECT 1 FROM resource_favorites WHERE resource_id=? AND user_id=?",id,user).stream().findFirst().orElse(null);
    }

    public Long favoriteCount(long id) {
        return db.queryForObject("SELECT COUNT(*) FROM resource_favorites WHERE resource_id=?",Long.class,id);
    }

    public Map<String,Object> likeBy(long id, long user) {
        return db.queryForList("SELECT 1 FROM resource_votes WHERE resource_id=? AND user_id=?",id,user).stream().findFirst().orElse(null);
    }

    public Long likeCount(long id) {
        return db.queryForObject("SELECT COUNT(*) FROM resource_votes WHERE resource_id=?",Long.class,id);
    }

    public Map<String,Object> lockResource(long id) {
        return db.queryForList("SELECT * FROM resources WHERE id=? FOR UPDATE",id).stream().findFirst().orElse(null);
    }

    public List<Map<String,Object>> downloadFiles(long id) {
        return db.queryForList("SELECT id,file_name,size_bytes FROM resource_files WHERE resource_id=?",id);
    }

    public Map<String,Object> downloadBy(long id, long me) {
        return db.queryForList("SELECT id FROM resource_downloads WHERE resource_id=? AND user_id=? LIMIT 1",id,me).stream().findFirst().orElse(null);
    }

    public int chargeDownload(long cost, long me) {
        return db.update("UPDATE users SET points=points-? WHERE id=? AND points>=?",cost,me,cost);
    }

    public int recordDownloadCharge(long cost, long id, long me) {
        return db.update("INSERT INTO point_logs(user_id,action,points,balance_after,ref_type,ref_id) SELECT id,'resource_download',?,points,'resource',? FROM users WHERE id=?",-cost,id,me);
    }

    public int recordDownload(long id, long me) {
        return db.update("INSERT INTO resource_downloads(resource_id,user_id) VALUES(?,?)",id,me);
    }

    public int incrementDownloads(long id) {
        return db.update("UPDATE resources SET download_count=download_count+1 WHERE id=?",id);
    }

    public Map<String,Object> file(long fileId, long id) {
        return db.queryForList("SELECT * FROM resource_files WHERE id=? AND resource_id=?",fileId,id).stream().findFirst().orElse(null);
    }

    public void toggleInteraction(long id, long user, boolean favorite, boolean active) {
        String table=favorite?"resource_favorites":"resource_votes";
        if(active) db.update("INSERT IGNORE INTO "+table+"(resource_id,user_id) VALUES(?,?)",id,user);
        else db.update("DELETE FROM "+table+" WHERE resource_id=? AND user_id=?",id,user);
    }

    public int addComment(long id, long user, String content) {
        return db.update("INSERT INTO resource_comments(resource_id,user_id,content) VALUES(?,?,?)",id,user,content);
    }

    public Long dailyReportCount(long user) {
        return db.queryForObject("SELECT COUNT(*) FROM reports WHERE reporter_id=? AND created_at>=CURRENT_DATE",Long.class,user);
    }

    public int report(long user, long id, String reason, String description) {
        return db.update("INSERT INTO reports(reporter_id,target_type,target_id,reason,description) VALUES(?,'resource',?,?,?)",user,id,reason,description);
    }

    public List<Map<String,Object>> downloads(long user) {
        return db.queryForList("SELECT d.*,r.title FROM resource_downloads d JOIN resources r ON r.id=d.resource_id WHERE d.user_id=? ORDER BY d.id DESC LIMIT 100",user);
    }

    public List<Map<String,Object>> adminResources(int offset) {
        return db.queryForList("SELECT r.*,u.username FROM resources r JOIN users u ON u.id=r.uploader_id ORDER BY r.status='pending' DESC,r.id DESC LIMIT 21 OFFSET ?",offset);
    }

    public Map<String,Object> findId(long id) {
        return db.queryForList("SELECT id FROM resources WHERE id=?",id).stream().findFirst().orElse(null);
    }

    public int setStatus(String status, long id) {
        return db.update("UPDATE resources SET status=? WHERE id=?",status,id);
    }

    public long insertUpload(long user, String name, String stored, String mime, int size) {
        var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
        db.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO resource_uploads(user_id,file_name,stored_name,mime_type,size_bytes) VALUES(?,?,?,?,?)", java.sql.Statement.RETURN_GENERATED_KEYS);
            Object[] fields = {user, name, stored, mime, size};
            for (int i=0; i<fields.length; i++) statement.setObject(i+1,fields[i]);
            return statement;
        }, key);
        return java.util.Objects.requireNonNull(key.getKey()).longValue();
    }

    public long insertResource(long user, String title, String description, String subject, String grade, String textbook, String exam, String tags, String source, String copyrightNote, long points) {
        var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
        db.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO resources(uploader_id,title,description,subject,grade,textbook,exam,tags,source,copyright_note,download_points) VALUES(?,?,?,?,?,?,?,?,?,?,?)", java.sql.Statement.RETURN_GENERATED_KEYS);
            Object[] fields = {user, title, description, subject, grade, textbook, exam, tags, source, copyrightNote, points};
            for (int i=0; i<fields.length; i++) statement.setObject(i+1,fields[i]);
            return statement;
        }, key);
        return java.util.Objects.requireNonNull(key.getKey()).longValue();
    }
}
