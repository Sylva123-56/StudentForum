package com.studentforum.repository;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StudyRoomRepository {
    private final JdbcTemplate db;

    public StudyRoomRepository(JdbcTemplate db) {
        this.db = db;
    }

    public Map<String, Object> details(long roomId) {
        return db.queryForList("SELECT r.*,u.username AS owner_username,(SELECT COUNT(*) FROM study_room_members m WHERE m.room_id=r.id) AS online_members"
                + " FROM study_rooms r JOIN users u ON u.id=r.owner_id WHERE r.id=?", roomId).stream().findFirst().orElse(null);
    }

    public boolean isMember(long roomId, long userId) {
        return !db.queryForList("SELECT id FROM study_room_members WHERE room_id=? AND user_id=?", roomId, userId).isEmpty();
    }

    public void refreshTotals(long roomId) {
        db.update("UPDATE study_rooms SET member_count=(SELECT COUNT(*) FROM study_room_members WHERE room_id=?),"
                + "focus_minutes=(SELECT COALESCE(SUM(duration_minutes),0) FROM focus_sessions WHERE room_id=?) WHERE id=?", roomId, roomId, roomId);
    }

    public Map<String, Object> findWithOwner(long roomId) {
        return db.queryForList("SELECT r.*,u.username AS owner_username FROM study_rooms r JOIN users u ON u.id=r.owner_id WHERE r.id=?", roomId).stream().findFirst().orElse(null);
    }

    public List<Map<String, Object>> membership(long roomId, long user) {
        return db.queryForList("SELECT id FROM study_room_members WHERE room_id=? AND user_id=?", roomId, user);
    }

    public List<Map<String, Object>> onlineMembers(long roomId) {
        return db.queryForList("SELECT m.user_id,u.username,m.total_minutes,m.joined_at,m.last_active_at FROM study_room_members m JOIN users u ON u.id=m.user_id WHERE m.room_id=? ORDER BY m.total_minutes DESC LIMIT 50", roomId);
    }

    public Map<String, Object> groupName(long groupId) {
        return db.queryForList("SELECT name FROM study_groups WHERE id=?", groupId).stream().findFirst().orElse(null);
    }

    public List<Map<String, Object>> myRooms(long user, long owner) {
        return db.queryForList("SELECT r.id,r.name,r.status,r.member_count,r.focus_minutes,m.total_minutes FROM study_rooms r LEFT JOIN study_room_members m ON m.room_id=r.id AND m.user_id=? WHERE r.owner_id=? OR m.user_id IS NOT NULL ORDER BY r.created_at DESC LIMIT 20", user, owner);
    }

    public Map<String, Object> groupMembership(Long groupId, long user) {
        return db.queryForList("SELECT 1 FROM study_group_members WHERE group_id=? AND user_id=?", groupId, user).stream().findFirst().orElse(null);
    }

    public long insertRoom(long user, String name, String goal, String subject, String visibility, boolean muted, int minMinutes, Long groupId) {
        db.update("INSERT INTO study_rooms(owner_id,name,goal,subject,visibility,status,muted,min_minutes,group_id,member_count) VALUES(?,?,?,?,?,'active',?,?,?,0)", user, name, goal, subject, visibility, muted, minMinutes, groupId);
        return db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public void addOwner(long roomId, long user) {
        db.update("INSERT INTO study_room_members(room_id,user_id,role,total_minutes,joined_at,last_active_at) VALUES(?,?,'owner',0,NOW(),NOW())", roomId, user);
    }

    public void initializeMemberCount(long roomId) {
        db.update("UPDATE study_rooms SET member_count=1 WHERE id=?", roomId);
    }

    public List<Map<String, Object>> otherGroupMembers(Long groupId, long user) {
        return db.queryForList("SELECT user_id FROM study_group_members WHERE group_id=? AND user_id<>?", groupId, user);
    }

    public void notifyRoomOpened(long user, String message) {
        db.update("INSERT INTO notifications(user_id,kind,message) VALUES(?,'room',?)", user, message);
    }

    public List<Map<String, Object>> messages(long roomId, int limit) {
        return db.queryForList("SELECT m.id,m.kind,m.content,m.created_at,m.user_id,u.username FROM study_room_messages m JOIN users u ON u.id=m.user_id WHERE m.room_id=? ORDER BY m.id DESC LIMIT ?", roomId, limit);
    }

    public Map<String, Object> findRoom(long roomId) {
        return db.queryForList("SELECT * FROM study_rooms WHERE id=?", roomId).stream().findFirst().orElse(null);
    }

    public void addMember(long roomId, long user) {
        db.update("INSERT INTO study_room_members(room_id,user_id,role,total_minutes,joined_at,last_active_at) VALUES(?,?,'member',0,NOW(),NOW())", roomId, user);
    }

    public void removeMember(long roomId, long user) {
        db.update("DELETE FROM study_room_members WHERE room_id=? AND user_id=?", roomId, user);
    }

    public void updateRoom(String name, String goal, String subject, String visibility, boolean muted, String status, int minMinutes, long roomId) {
        db.update("UPDATE study_rooms SET name=?,goal=?,subject=?,visibility=?,muted=?,status=?,min_minutes=? WHERE id=?", name, goal, subject, visibility, muted, status, minMinutes, roomId);
    }

    public void recordOwnerClose(long roomId, long user) {
        db.update("INSERT INTO study_room_messages(room_id,user_id,kind,content) VALUES(?,?,'system','房主关闭了自习室')", roomId, user);
    }

    public void deleteRoom(long roomId) {
        db.update("DELETE FROM study_rooms WHERE id=?", roomId);
    }

    public List<Map<String, Object>> activeMembers(long roomId) {
        return db.queryForList("SELECT m.user_id,u.username,m.total_minutes,m.last_active_at FROM study_room_members m JOIN users u ON u.id=m.user_id WHERE m.room_id=? ORDER BY m.total_minutes DESC LIMIT 50", roomId);
    }

    public long insertMessage(long roomId, long user, String kind, String content) {
        db.update("INSERT INTO study_room_messages(room_id,user_id,kind,content) VALUES(?,?,?,?)", roomId, user, kind, content);
        return db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public void touchMember(long roomId, long user) {
        db.update("UPDATE study_room_members SET last_active_at=NOW() WHERE room_id=? AND user_id=?", roomId, user);
    }

    public Map<String, Object> findMessage(long messageId) {
        return db.queryForList("SELECT m.id,m.kind,m.content,m.created_at,m.user_id,u.username FROM study_room_messages m JOIN users u ON u.id=m.user_id WHERE m.id=?", messageId).stream().findFirst().orElse(null);
    }

    public Map<String, Object> findRoomMessage(long messageId, long roomId) {
        return db.queryForList("SELECT * FROM study_room_messages WHERE id=? AND room_id=?", messageId, roomId).stream().findFirst().orElse(null);
    }

    public void deleteMessage(long messageId) {
        db.update("DELETE FROM study_room_messages WHERE id=?", messageId);
    }

    public List<Map<String, Object>> leaderboard(long roomId) {
        return db.queryForList("SELECT m.user_id,u.username,m.role,m.total_minutes,m.joined_at FROM study_room_members m JOIN users u ON u.id=m.user_id WHERE m.room_id=? ORDER BY m.total_minutes DESC,u.username LIMIT 50", roomId);
    }

    public List<Map<String, Object>> groupRooms(long groupId) {
        return db.queryForList("SELECT r.*,u.username AS owner_username FROM study_rooms r JOIN users u ON u.id=r.owner_id WHERE r.group_id=? AND r.status='active' ORDER BY r.created_at DESC LIMIT 20", groupId);
    }

    public List<Map<String, Object>> rooms(Long user, String scope) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT r.*,u.username AS owner_username,(SELECT COUNT(*) FROM study_room_members m WHERE m.room_id=r.id) AS members FROM study_rooms r JOIN users u ON u.id=r.owner_id WHERE 1=1");
        boolean mine = "mine".equals(scope) || "joined".equals(scope);
        if (mine) {
            sql.append(" AND (r.owner_id=? OR r.id IN (SELECT room_id FROM study_room_members WHERE user_id=?))");
            args.add(user);
            args.add(user);
        } else if ("host".equals(scope)) {
            sql.append(" AND r.owner_id=?");
            args.add(user);
        } else {
            // 默认只逛公开自习室；私密房间只能靠房主邀请/直链进入
            sql.append(" AND r.visibility='public'");
        }
        sql.append(" AND r.status='active' ORDER BY r.member_count DESC,r.created_at DESC LIMIT 60");
        return db.queryForList(sql.toString(), args.toArray());
    }
}
