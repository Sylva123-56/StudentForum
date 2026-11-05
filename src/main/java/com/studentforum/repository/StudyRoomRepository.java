package com.studentforum.repository;

import java.util.Map;
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
}
