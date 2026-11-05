package com.studentforum.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class GroupRepository {
    private final JdbcTemplate db;

    public GroupRepository(JdbcTemplate db) {
        this.db = db;
    }

    public List<Map<String, Object>> rankings(String type) {
        String order = "members".equals(type) ? "member_count DESC" : "subject".equals(type) ? "subject,activity_score DESC" : "activity_score DESC";
        return db.queryForList("SELECT g.id,g.name,g.subject,g.member_count,g.post_count,g.activity_score,u.username AS owner_username FROM study_groups g JOIN users u ON u.id=g.owner_id WHERE g.status='active' ORDER BY " + order + ",g.created_at DESC LIMIT 20");
    }

    public List<Map<String, Object>> recommended(long user, String subject) {
        return db.queryForList("SELECT g.*,u.username AS owner_username FROM study_groups g JOIN users u ON u.id=g.owner_id WHERE g.status='active' AND g.visibility='public' AND g.id NOT IN (SELECT group_id FROM study_group_members WHERE user_id=?) ORDER BY (CASE WHEN ?<>'' AND g.subject=? THEN 0 ELSE 1 END),g.activity_score DESC,g.created_at DESC LIMIT 12", user, subject, subject);
    }

    public List<Map<String, Object>> search(String keyword,
                                          String tag,
                                          String subject,
                                          String school,
                                          String status,
                                          String sort) {
        StringBuilder sql = new StringBuilder("SELECT g.*,u.username AS owner_username FROM study_groups g JOIN users u ON u.id=g.owner_id WHERE g.status=?");
        List<Object> args = new ArrayList<>(List.of(status));
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (LOCATE(LOWER(?),LOWER(g.name))>0 OR LOCATE(LOWER(?),LOWER(g.description))>0 OR LOCATE(LOWER(?),LOWER(g.tags))>0)");
            String q = keyword.trim();
            args.add(q);
            args.add(q);
            args.add(q);
        }
        if (tag != null && !tag.isBlank()) {
            sql.append(" AND LOCATE(LOWER(?),LOWER(g.tags))>0");
            args.add(tag.trim());
        }
        if (subject != null && !subject.isBlank()) {
            sql.append(" AND g.subject=?");
            args.add(subject.trim());
        }
        if (school != null && !school.isBlank()) {
            sql.append(" AND g.school=?");
            args.add(school.trim());
        }
        sql.append(" ORDER BY ").append("members".equals(sort) ? "g.member_count DESC," : "activity".equals(sort) ? "g.activity_score DESC," : "g.created_at DESC,").append(" g.created_at DESC LIMIT 100");
        return db.queryForList(sql.toString(), args.toArray());
    }


    public long insertGroup(String name, String description, Object avatar, String tags, String subject, String school, long user, String visibility, String joinMode) {
        var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
        db.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO study_groups(name,description,avatar_url,tags,subject,school,owner_id,visibility,join_mode,member_count) VALUES(?,?,?,?,?,?,?,?,?,1)", java.sql.Statement.RETURN_GENERATED_KEYS);
            Object[] fields = {name, description, avatar, tags, subject, school, user, visibility, joinMode};
            for (int i = 0; i < fields.length; i++) statement.setObject(i + 1, fields[i]);
            return statement;
        }, key);
        return java.util.Objects.requireNonNull(key.getKey()).longValue();
    }

    public int addOwner(long groupId, long user) {
        return db.update("INSERT INTO study_group_members(group_id,user_id,role) VALUES(?,?, 'owner')", groupId, user);
    }

    public List<Map<String, Object>> announcements(long groupId) {
        return db.queryForList("SELECT a.*,u.username AS author_username FROM study_group_announcements a JOIN users u ON u.id=a.author_id WHERE a.group_id=? ORDER BY a.created_at DESC LIMIT 10", groupId);
    }

    public List<Map<String, Object>> files(long groupId) {
        return db.queryForList("SELECT f.*,u.username AS uploader_username FROM study_group_files f JOIN users u ON u.id=f.uploader_id WHERE f.group_id=? ORDER BY f.created_at DESC LIMIT 20", groupId);
    }

    public List<Map<String, Object>> membership(long groupId, long userId) {
        return db.queryForList("SELECT * FROM study_group_members WHERE group_id=? AND user_id=?", groupId, userId);
    }

    public List<Map<String, Object>> pendingApplication(long groupId, long userId) {
        return db.queryForList("SELECT id,status,message FROM study_group_join_requests WHERE group_id=? AND user_id=? AND status='pending'", groupId, userId);
    }

    public int updateGroup(String name, String description, Object avatar, Object tags, Object subject, Object school, String visibility, String joinMode, long groupId) {
        return db.update("UPDATE study_groups SET name=?,description=?,avatar_url=?,tags=?,subject=?,school=?,visibility=?,join_mode=? WHERE id=?", name, description, avatar, tags, subject, school, visibility, joinMode, groupId);
    }

    public int disableGroup(long groupId) {
        return db.update("UPDATE study_groups SET status='disabled' WHERE id=?", groupId);
    }

    public List<Map<String, Object>> pendingRequest(long groupId, long user) {
        return db.queryForList("SELECT id FROM study_group_join_requests WHERE group_id=? AND user_id=? AND status='pending'", groupId, user);
    }

    public List<Map<String, Object>> latestRequest(long groupId, long user) {
        return db.queryForList("SELECT id FROM study_group_join_requests WHERE group_id=? AND user_id=? ORDER BY id DESC LIMIT 1", groupId, user);
    }

    public int createRequest(long groupId, long user, String message) {
        return db.update("INSERT INTO study_group_join_requests(group_id,user_id,message) VALUES(?,?,?)", groupId, user, message);
    }

    public int removeOlderRequests(long groupId, long user, long requestId) {
        return db.update("DELETE FROM study_group_join_requests WHERE group_id=? AND user_id=? AND id<>?", groupId, user, requestId);
    }

    public int reopenRequest(String message, long requestId) {
        return db.update("UPDATE study_group_join_requests SET status='pending',message=?,reviewer_id=NULL,handled_at=NULL,created_at=NOW() WHERE id=?", message, requestId);
    }

    public int notifyUser(long user, String kind, String message) {
        return db.update("INSERT INTO notifications(user_id,kind,message) VALUES(?,?,?)", user, kind, message);
    }

    public int removeMember(long groupId, long userId) {
        return db.update("DELETE FROM study_group_members WHERE group_id=? AND user_id=?", groupId, userId);
    }

    public int decrementMemberCount(long groupId) {
        return db.update("UPDATE study_groups SET member_count=GREATEST(0,member_count-1) WHERE id=?", groupId);
    }

    public int demoteOwner(long groupId) {
        return db.update("UPDATE study_group_members SET role='admin' WHERE group_id=? AND role='owner'", groupId);
    }

    public int transferOwner(long userId, long groupId) {
        return db.update("UPDATE study_groups SET owner_id=? WHERE id=?", userId, groupId);
    }

    public int setMemberRole(String role, long groupId, long userId) {
        return db.update("UPDATE study_group_members SET role=? WHERE group_id=? AND user_id=?", role, groupId, userId);
    }

    public long insertPost(long groupId, long user, String title, String content, Object image, String tags) {
        var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
        db.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO study_group_posts(group_id,author_id,title,content,image_path,tags) VALUES(?,?,?,?,?,?)", java.sql.Statement.RETURN_GENERATED_KEYS);
            Object[] fields = {groupId, user, title, content, image, tags};
            for (int i = 0; i < fields.length; i++) statement.setObject(i + 1, fields[i]);
            return statement;
        }, key);
        return java.util.Objects.requireNonNull(key.getKey()).longValue();
    }

    public int recordPostActivity(long groupId) {
        return db.update("UPDATE study_groups SET post_count=post_count+1,activity_score=activity_score+3,updated_at=NOW() WHERE id=?", groupId);
    }

    public int insertReply(long postId, long user, String content) {
        return db.update("INSERT INTO study_group_post_replies(post_id,author_id,content) VALUES(?,?,?)", postId, user, content);
    }

    public int recordReplyActivity(long groupId) {
        return db.update("UPDATE study_groups SET activity_score=activity_score+1 WHERE id=?", groupId);
    }

    public int likePost(long postId, long user) {
        return db.update("INSERT IGNORE INTO study_group_post_likes(post_id,user_id) VALUES(?,?)", postId, user);
    }

    public int unlikePost(long postId, long user) {
        return db.update("DELETE FROM study_group_post_likes WHERE post_id=? AND user_id=?", postId, user);
    }

    public int favoritePost(long postId, long user) {
        return db.update("INSERT IGNORE INTO study_group_post_favorites(post_id,user_id) VALUES(?,?)", postId, user);
    }

    public int unfavoritePost(long postId, long user) {
        return db.update("DELETE FROM study_group_post_favorites WHERE post_id=? AND user_id=?", postId, user);
    }

    public int setPostStatus(String status, long postId, long groupId) {
        return db.update("UPDATE study_group_posts SET status=? WHERE id=? AND group_id=?", status, postId, groupId);
    }

    public int setPostTop(boolean top, long postId, long groupId) {
        return db.update("UPDATE study_group_posts SET is_top=? WHERE id=? AND group_id=?", top, postId, groupId);
    }

    public int setPostFeatured(boolean featured, long postId, long groupId) {
        return db.update("UPDATE study_group_posts SET is_featured=? WHERE id=? AND group_id=?", featured, postId, groupId);
    }

    public int insertAnnouncement(long groupId, long user, String title, String content) {
        return db.update("INSERT INTO study_group_announcements(group_id,author_id,title,content) VALUES(?,?,?,?)", groupId, user, title, content);
    }

    public int insertFile(long groupId, long user, String name, String path, long size, boolean allowDownload) {
        return db.update("INSERT INTO study_group_files(group_id,uploader_id,name,path,size_bytes,allow_download) VALUES(?,?,?,?,?,?)", groupId, user, name, path, size, allowDownload);
    }

    public List<Map<String, Object>> findFile(long fileId, long groupId) {
        return db.queryForList("SELECT * FROM study_group_files WHERE id=? AND group_id=?", fileId, groupId);
    }

    public int deleteFile(long fileId) {
        return db.update("DELETE FROM study_group_files WHERE id=?", fileId);
    }

    public int insertCheckin(long groupId, long user, String title, String description, Object dueAt) {
        return db.update("INSERT INTO study_group_checkins(group_id,creator_id,title,description,due_at) VALUES(?,?,?,?,?)", groupId, user, title, description, dueAt);
    }

    public int recordCheckin(long checkinId, long user, String content) {
        return db.update("INSERT IGNORE INTO study_group_checkin_records(checkin_id,user_id,content) VALUES(?,?,?)", checkinId, user, content);
    }

    public int recordCheckinActivity(long groupId) {
        return db.update("UPDATE study_groups SET activity_score=activity_score+2 WHERE id=?", groupId);
    }

    public int insertReport(long groupId, long user, String type, long target, String reason, String description) {
        return db.update("INSERT INTO study_group_reports(group_id,reporter_id,target_type,target_id,reason,description) VALUES(?,?,?,?,?,?)", groupId, user, type, target, reason, description);
    }

    public List<Map<String, Object>> reports(long groupId) {
        return db.queryForList("SELECT r.*,u.username AS reporter_username FROM study_group_reports r JOIN users u ON u.id=r.reporter_id WHERE r.group_id=? ORDER BY r.status,r.created_at DESC", groupId);
    }

    public int reviewReport(String status, long user, long reportId, long groupId) {
        return db.update("UPDATE study_group_reports SET status=?,handler_id=?,handled_at=NOW() WHERE id=? AND group_id=?", status, user, reportId, groupId);
    }

    public List<Map<String, Object>> joinRequests(long groupId) {
        return db.queryForList("SELECT r.*,u.username FROM study_group_join_requests r JOIN users u ON u.id=r.user_id WHERE r.group_id=? ORDER BY r.status,r.created_at DESC", groupId);
    }

    public List<Map<String, Object>> reviewableRequest(long requestId, long groupId) {
        return db.queryForList("SELECT * FROM study_group_join_requests WHERE id=? AND group_id=? AND status='pending'", requestId, groupId);
    }

    public int reviewRequest(String status, long actor, long requestId) {
        return db.update("UPDATE study_group_join_requests SET status=?,reviewer_id=?,handled_at=NOW() WHERE id=?", status, actor, requestId);
    }

    public List<Map<String, Object>> adminGroups(String query) {
        return db.queryForList("SELECT g.*,u.username AS owner_username FROM study_groups g JOIN users u ON u.id=g.owner_id WHERE ?='' OR LOCATE(LOWER(?),LOWER(g.name))>0 OR LOCATE(LOWER(?),LOWER(g.description))>0 ORDER BY g.created_at DESC", query, query, query);
    }

    public int setGroupStatus(String status, long groupId) {
        return db.update("UPDATE study_groups SET status=? WHERE id=?", status, groupId);
    }

    public List<Map<String, Object>> allLogs() {
        return db.queryForList("SELECT l.*,u.username AS actor_username,g.name AS group_name FROM study_group_logs l LEFT JOIN users u ON u.id=l.actor_id JOIN study_groups g ON g.id=l.group_id ORDER BY l.created_at DESC LIMIT 200");
    }

    public List<Map<String, Object>> groupLogs(Long groupId) {
        return db.queryForList("SELECT l.*,u.username AS actor_username,g.name AS group_name FROM study_group_logs l LEFT JOIN users u ON u.id=l.actor_id JOIN study_groups g ON g.id=l.group_id WHERE l.group_id=? ORDER BY l.created_at DESC", groupId);
    }

    public List<Map<String, Object>> replies(long postId) {
        return db.queryForList("SELECT r.*,u.username AS author_username FROM study_group_post_replies r JOIN users u ON u.id=r.author_id WHERE r.post_id=? ORDER BY r.created_at ASC", postId);
    }

    public List<Map<String, Object>> findGroup(long id) {
        return db.queryForList("SELECT g.*,u.username AS owner_username FROM study_groups g JOIN users u ON u.id=g.owner_id WHERE g.id=?", id);
    }

    public List<Map<String, Object>> members(long groupId) {
        return db.queryForList("SELECT m.*,u.username,u.points,u.reputation,(SELECT COUNT(*) FROM study_group_posts p WHERE p.group_id=m.group_id AND p.author_id=m.user_id) AS contribution FROM study_group_members m JOIN users u ON u.id=m.user_id WHERE m.group_id=? ORDER BY FIELD(m.role,'owner','admin','member'),m.joined_at", groupId);
    }

    public int addMember(long groupId, long userId, String role) {
        return db.update("INSERT IGNORE INTO study_group_members(group_id,user_id,role) VALUES(?,?,?)", groupId, userId, role);
    }

    public int refreshMemberCount(long groupId) {
        return db.update("UPDATE study_groups SET member_count=(SELECT COUNT(*) FROM study_group_members WHERE group_id=?) WHERE id=?", groupId, groupId);
    }

    public int notifyManagers(String message, long groupId) {
        return db.update("INSERT INTO notifications(user_id,kind,message) SELECT user_id,'group',? FROM study_group_members WHERE group_id=? AND role IN ('owner','admin')", message, groupId);
    }

    public int notifyMembers(String message, long groupId) {
        return db.update("INSERT INTO notifications(user_id,kind,message) SELECT user_id,'group',? FROM study_group_members WHERE group_id=?", message, groupId);
    }

    public int log(long groupId, long actor, String action, String type, long target, String detail) {
        return db.update("INSERT INTO study_group_logs(group_id,actor_id,action,target_type,target_id,detail) VALUES(?,?,?,?,?,?)", groupId, actor, action, type, target, detail);
    }

    public List<Map<String, Object>> checkins(long groupId, Long user) {
        String select = "SELECT c.*,u.username AS creator_username,(SELECT COUNT(*) FROM study_group_checkin_records r WHERE r.checkin_id=c.id) AS completed_count" + (user == null ? ",FALSE AS completed" : " ,EXISTS(SELECT 1 FROM study_group_checkin_records r2 WHERE r2.checkin_id=c.id AND r2.user_id=?) AS completed") + " FROM study_group_checkins c JOIN users u ON u.id=c.creator_id WHERE c.group_id=? ORDER BY c.created_at DESC";
        return user == null ? db.queryForList(select, groupId) : db.queryForList(select, user, groupId);
    }

    public List<Map<String, Object>> posts(long groupId, Long user, int limit) {
        String sql = "SELECT p.*,u.username AS author_username,(SELECT COUNT(*) FROM study_group_post_replies r WHERE r.post_id=p.id) AS reply_count,(SELECT COUNT(*) FROM study_group_post_likes l WHERE l.post_id=p.id) AS like_count,(SELECT COUNT(*) FROM study_group_post_favorites f WHERE f.post_id=p.id) AS favorite_count" + (user == null ? ",FALSE AS liked,FALSE AS favorited" : " ,EXISTS(SELECT 1 FROM study_group_post_likes l2 WHERE l2.post_id=p.id AND l2.user_id=?) AS liked,EXISTS(SELECT 1 FROM study_group_post_favorites f2 WHERE f2.post_id=p.id AND f2.user_id=?) AS favorited") + " FROM study_group_posts p JOIN users u ON u.id=p.author_id WHERE p.group_id=? AND p.status='published' ORDER BY p.is_top DESC,p.is_featured DESC,p.created_at DESC LIMIT " + limit;
        return user == null ? db.queryForList(sql, groupId) : db.queryForList(sql, user, user, groupId);
    }
}
