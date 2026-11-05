package com.studentforum.service;

import com.studentforum.repository.StudyRoomRepository;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StudyRoomAccessService {
    private final StudyRoomRepository repository;

    public StudyRoomAccessService(StudyRoomRepository repository) {
        this.repository = repository;
    }

    // ---------- 自习室 ----------

    public Map<String, Object> room(long roomId) {
        Map<String, Object> result = repository.details(roomId);
        if (result == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "自习室不存在");
        return result;
    }

    /**
     * 自习室可见性：公开房间人人可看；私密房间只有房主、成员能看。
     * 排行榜/成员榜这类需要匿名的接口不走这里（它们在 SQL 里直接过滤 visibility='public'）。
     */
    public void requireRoomView(Map<String, Object> room, Long userId) {
        boolean member = userId != null && repository.isMember(number(room, "id"), userId);
        boolean owner = userId != null && number(room, "owner_id") == userId;
        if ("public".equals(room.get("visibility")) || member || owner) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "这是私密自习室，仅成员可以查看");
    }

    public void requireRoomOwner(Map<String, Object> room, long userId) {
        if (number(room, "owner_id") != userId) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅自习室创建者可以操作");
    }

    /** 总时长/成员数每次变化后重算，只信 focus_sessions 与成员表这两处事实。 */
    public void refreshRoomTotals(long roomId) {
        repository.refreshTotals(roomId);
    }

    private long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.longValue() : 0;
    }
}
