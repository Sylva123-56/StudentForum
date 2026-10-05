package com.studentforum;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

/**
 * 学习工具的纯单元测试：不起 Spring 上下文，用 Mockito 替掉 JdbcTemplate，
 * 验证的是一旦写错就会被打穿的那几条业务规则（重复打卡、时长门槛、补卡窗口、自习室权限）。
 */
class StudyToolsTest {
    private static final long ME = 7L;

    private JdbcTemplate db;
    private ForumService service;
    private ForumMapper mapper;
    private StudyToolService tools;
    private CheckinController checkin;
    private FocusController focus;
    private StudyRoomController rooms;
    private StudyPlanController plans;
    private Authentication auth;

    @BeforeEach void setup() {
        db = mock(JdbcTemplate.class);
        mapper = mock(ForumMapper.class);
        service = new ForumService(mapper, db);
        tools = new StudyToolService(db, service, mapper);
        checkin = new CheckinController(db, service, tools);
        focus = new FocusController(db, service, tools);
        rooms = new StudyRoomController(db, service, tools);
        plans = new StudyPlanController(db, service, tools);
        auth = new UsernamePasswordAuthenticationToken(String.valueOf(ME), null, List.of());

        // 默认查询返回空集：只有测试显式桩过的 SQL 才有数据，避免"碰巧通过"
        when(db.queryForList(anyString(), any(Object[].class))).thenAnswer(call -> rows(call.getArgument(0)));
        when(db.queryForList(anyString())).thenAnswer(call -> rows(call.getArgument(0)));
        // COUNT 类标量查询的兜底：mock 默认返回 null，直接拆箱会 NPE，这里统一先给 0
        when(db.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
        when(db.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        // 账号可写是绝大多数写接口的前置条件
        when(mapper.user(ME)).thenReturn(new LinkedHashMap<>(Map.of("id", ME, "status", "active", "reputation", 100, "role", "user")));
    }

    /** 按 SQL 片段分发数据，比逐个 when(...) 更耐得住控制器改查询顺序。 */
    private List<Map<String, Object>> rows(String sql) {
        String text = sql == null ? "" : sql;
        if (text.contains("FROM checkin_settings")) return List.of();
        if (text.contains("FROM user_badges")) return List.of();
        if (text.contains("FROM focus_sessions WHERE id=?")) return List.of(Map.of("id", 31L, "user_id", ME, "duration_minutes", 25, "source", "timer"));
        return List.of();
    }

    private static Map<String, Object> goal(long id, int targetMinutes, long createdDaysAgo, boolean allowMakeup, String visibility) {
        Map<String, Object> goal = new LinkedHashMap<>();
        goal.put("id", id);
        goal.put("user_id", ME);
        goal.put("title", "每天背单词");
        goal.put("subject", "英语");
        goal.put("target_minutes", targetMinutes);
        goal.put("visibility", visibility);
        goal.put("status", "active");
        goal.put("allow_makeup", allowMakeup);
        goal.put("created_at", LocalDate.now().minusDays(createdDaysAgo).atStartOfDay());
        return goal;
    }

    // ---------- 打卡 ----------

    @Test void rejectsSecondCheckinOnTheSameDayBeforeWriting() {
        Map<String, Object> goal = goal(3, 0, 30, true, "private");
        when(db.queryForList(argThat(sql -> sql != null && sql.contains("FROM checkin_goals WHERE id=?")), any(Object[].class))).thenReturn(List.of(goal));
        // 「今天是否已经打过卡」是一次 COUNT 查询，返回 1 就应当被拦住
        when(db.queryForObject(argThat(sql -> sql != null && sql.contains("? AND checkin_date=?")), eq(Long.class), eq(3L), any(LocalDate.class))).thenReturn(1L);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> checkin.checkin(auth, Map.of("goalId", 3, "content", "今天背了 50 个单词", "durationMinutes", 30)));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertTrue(error.getReason().contains("已经打过卡"));
        // 关键：一天一条是硬约束，冲突时绝不能再写库
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void rejectsCheckinShorterThanTheGoalTarget() {
        when(db.queryForList(argThat(sql -> sql != null && sql.contains("FROM checkin_goals WHERE id=?")), any(Object[].class))).thenReturn(List.of(goal(3, 30, 30, true, "private")));
        when(db.queryForObject(argThat(sql -> sql != null && sql.contains("? AND checkin_date=?")), eq(Long.class), eq(3L), any(LocalDate.class))).thenReturn(0L);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> checkin.checkin(auth, Map.of("goalId", 3, "content", "只背了 10 分钟", "durationMinutes", 10)));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertTrue(error.getReason().contains("30 分钟"));
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void rejectsMakeupBeyondTheTwoDayWindow() {
        when(db.queryForList(argThat(sql -> sql != null && sql.contains("FROM checkin_goals WHERE id=?")), any(Object[].class))).thenReturn(List.of(goal(3, 0, 30, true, "private")));
        when(db.queryForObject(argThat(sql -> sql != null && sql.contains("? AND checkin_date=?")), eq(Long.class), eq(3L), any(LocalDate.class))).thenReturn(0L);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> checkin.checkin(auth, Map.of("goalId", 3, "content", "补卡", "durationMinutes", 30, "date", LocalDate.now().minusDays(5).toString())));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void rejectsFutureCheckinDate() {
        when(db.queryForList(argThat(sql -> sql != null && sql.contains("FROM checkin_goals WHERE id=?")), any(Object[].class))).thenReturn(List.of(goal(3, 0, 30, true, "private")));
        when(db.queryForObject(argThat(sql -> sql != null && sql.contains("? AND checkin_date=?")), eq(Long.class), eq(3L), any(LocalDate.class))).thenReturn(0L);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> checkin.checkin(auth, Map.of("goalId", 3, "content", "提前打卡", "durationMinutes", 30, "date", LocalDate.now().plusDays(1).toString())));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void rejectsNewGoalWithoutTitle() {
        // 目标标题是必填，长度不够时连库都不该碰
        assertThrows(ResponseStatusException.class, () -> checkin.createGoal(auth, Map.of("title", "背")));
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void successfulCheckinReturnsTheRecordPointsAndStreak() {
        when(db.queryForList(argThat(sql -> sql != null && sql.contains("FROM checkin_goals WHERE id=?")), any(Object[].class)))
                .thenReturn(List.of(goal(3, 0, 30, true, "private")));
        when(db.queryForObject(argThat(sql -> sql != null && sql.contains("? AND checkin_date=?")), eq(Long.class), eq(3L), any(LocalDate.class))).thenReturn(0L);
        when(db.queryForObject(argThat(sql -> sql != null && sql.contains("SELECT LAST_INSERT_ID()")), eq(Long.class))).thenReturn(11L);
        // 回读打卡记录走的是 tools.requiredRow → db.queryForList，不是 queryForObject
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("id", 11L);
        stored.put("user_id", ME);
        stored.put("goal_id", 3L);
        stored.put("checkin_date", LocalDate.now());
        stored.put("duration_minutes", 30);
        stored.put("content", "背了 50 个单词");
        when(db.queryForList(argThat(sql -> sql != null && sql.contains("FROM checkins WHERE id=?")), any(Object[].class))).thenReturn(List.of(stored));
        // 今天已经打过卡，连续天数才是 1；不给这个桩的话 streak 会是 0
        when(db.queryForList(argThat(sql -> sql != null && sql.contains("FROM checkins WHERE goal_id=? AND user_id=?")), any(Object[].class)))
                .thenReturn(List.of(Map.of("checkin_date", LocalDate.now())));

        Map<String, Object> result = checkin.checkin(auth, Map.of("goalId", 3, "content", "背了 50 个单词", "durationMinutes", 30));

        // 前端读的是 checkin / points / pointsAwarded / streak 这几个键，缺一个都会让提示文案落空
        assertEquals(11L, result.get("checkin") instanceof Map<?, ?> body ? body.get("id") : null);
        assertEquals(2, ((Number) result.get("pointsAwarded")).intValue());
        assertEquals(2, ((Number) result.get("points")).intValue());
        assertEquals(1, ((Number) result.get("streak")).intValue());
        verify(db).update(argThat(sql -> sql != null && sql.contains("INSERT INTO checkins")),
                eq(ME), eq(3L), eq("背了 50 个单词"), eq(30), eq("英语"), isNull(), eq("private"), any(LocalDate.class), eq(false), eq("manual"), isNull(), isNull());
    }

    @Test void badgeCatalogIsExposedThroughTheBadgesEndpoint() {
        when(db.queryForList(argThat(sql -> sql != null && sql.contains("FROM user_badges")), any(Object[].class))).thenReturn(List.of());
        Object items = checkin.badges(auth).get("items");
        assertTrue(items instanceof List<?> list && !list.isEmpty());
    }

    @Test void continuingAStreakSkipsMissingToday() {
        // 昨天打过、今天还没打，连续天数仍然是 1（不该因为"今天还没到"就清零）
        List<LocalDate> dates = List.of(LocalDate.now().minusDays(1), LocalDate.now().minusDays(2), LocalDate.now().minusDays(3));
        assertEquals(3, tools.streak(dates, LocalDate.now()));
        // 今天打过则从今天开始数，重复日期不重复计数
        assertEquals(2, tools.streak(List.of(LocalDate.now(), LocalDate.now(), LocalDate.now().minusDays(1)), LocalDate.now()));
        assertEquals(0, tools.streak(List.of(LocalDate.now().minusDays(4)), LocalDate.now()));
    }

    // ---------- 番茄钟 ----------

    @Test void rejectsFocusSessionWithoutDuration() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> focus.createSession(auth, Map.of("subject", "数学")));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertTrue(error.getReason().contains("1 到 720"));
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void rejectsFocusSessionLongerThanTwelveHours() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> focus.createSession(auth, Map.of("durationMinutes", 721)));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void recordsFocusSessionAndReadsItBack() {
        Map<String, Object> created = focus.createSession(auth, Map.of("durationMinutes", 25, "subject", "数学", "task", "刷题", "source", "timer"));

        assertEquals(31L, created.get("id"));
        verify(db).update(argThat(sql -> sql != null && sql.contains("INSERT INTO focus_sessions")), eq(ME), eq("数学"), eq("刷题"), eq(""), eq(25), any(), any(), isNull(), isNull(), isNull(), eq("timer"));
    }

    // ---------- 自习室 ----------

    @Test void hidesPrivateRoomFromOutsiders() {
        Map<String, Object> room = new LinkedHashMap<>();
        room.put("id", 5L);
        room.put("owner_id", 99L);
        room.put("visibility", "private");
        room.put("status", "active");
        room.put("group_id", null);
        when(db.queryForList(argThat(sql -> sql != null && sql.contains("FROM study_rooms r JOIN users u")), any(Object[].class))).thenReturn(List.of(room));
        when(db.queryForList(argThat(sql -> sql != null && sql.contains("FROM study_group_members")), any(Object[].class))).thenReturn(List.of());

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> rooms.room(auth, 5));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
    }

    @Test void publicRoomIsVisibleWithoutMembership() {
        Map<String, Object> room = new LinkedHashMap<>();
        room.put("id", 5L);
        room.put("owner_id", 99L);
        room.put("visibility", "public");
        room.put("status", "active");
        room.put("group_id", null);
        when(db.queryForList(argThat(sql -> sql != null && sql.contains("FROM study_rooms r JOIN users u")), any(Object[].class))).thenReturn(List.of(room));

        Map<String, Object> result = rooms.room(auth, 5);
        assertEquals(5L, result.get("id"));
        assertEquals(false, result.get("joined"));
    }

    @Test void closedRoomCannotBeJoined() {
        when(db.queryForList(argThat(sql -> sql != null && sql.contains("FROM study_rooms WHERE id=?")), any(Object[].class)))
                .thenReturn(List.of(new LinkedHashMap<>(Map.of("id", 5L, "owner_id", 99L, "visibility", "public", "status", "closed"))));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> rooms.join(auth, 5));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertTrue(error.getReason().contains("已关闭"));
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void onlyTheOwnerCanMuteARoom() {
        Map<String, Object> room = new LinkedHashMap<>(Map.of("id", 5L, "owner_id", 99L, "visibility", "public", "status", "active", "name", "自习室"));
        when(db.queryForList(argThat(sql -> sql != null && sql.contains("FROM study_rooms WHERE id=?")), any(Object[].class))).thenReturn(List.of(room));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> rooms.updateRoom(auth, 5, Map.of("muted", true)));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    // ---------- 学习计划 ----------

    @Test void parentTaskCannotBeCheckedOffDirectly() {
        Map<String, Object> plan = new LinkedHashMap<>(Map.of("id", 4L, "user_id", ME, "title", "期末复习", "subject", "数学", "goal", "", "status", "active"));
        Map<String, Object> parent = new LinkedHashMap<>(Map.of("id", 9L, "plan_id", 4L, "title", "复习第一章", "status", "pending"));
        when(db.queryForList(argThat(sql -> sql != null && sql.contains("FROM study_plans WHERE id=?")), any(Object[].class))).thenReturn(List.of(plan));
        when(db.queryForList(argThat(sql -> sql != null && sql.contains("FROM study_tasks WHERE id=?")), any(Object[].class))).thenReturn(List.of(parent));
        // 子任务数量也是通过 tools.row(...) → queryForList 取的
        when(db.queryForList(argThat(sql -> sql != null && sql.contains("COUNT(*) AS count FROM study_tasks WHERE parent_id=?")), any(Object[].class)))
                .thenReturn(List.of(Map.of("count", 2L)));

        // 父任务的状态由子任务推导，直接勾父任务会让进度"一跳到底"，必须拦住
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> plans.updateTask(auth, 4, 9, Map.of("status", "done")));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertTrue(error.getReason().contains("子任务"));
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void archivedPlanCanBeRestored() {
        Map<String, Object> plan = new LinkedHashMap<>(Map.of("id", 4L, "user_id", ME, "title", "期末复习", "subject", "数学", "goal", "", "status", "archived"));
        when(db.queryForList(argThat(sql -> sql != null && sql.contains("FROM study_plans WHERE id=?")), any(Object[].class))).thenReturn(List.of(plan));

        plans.archivePlan(auth, 4, Map.of("archived", false));

        verify(db).update(argThat(sql -> sql != null && sql.contains("UPDATE study_plans SET status=?")), eq("active"), eq(4L));
    }
}
