package com.studentforum.service;

import com.studentforum.repository.StudyPlanRepository;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StudyPlanTaskServiceTest {
    private final StudyPlanRepository repository = mock(StudyPlanRepository.class);
    private final AccountAccessService accounts = mock(AccountAccessService.class);
    private final StudySettingsService settings = mock(StudySettingsService.class);
    private final StudyRewardService rewards = mock(StudyRewardService.class);
    private final StudyPlanProgressService progress = mock(StudyPlanProgressService.class);
    private final StudyPlanService plans = mock(StudyPlanService.class);
    private final Authentication auth = mock(Authentication.class);
    private final StudyPlanTaskService tasks = new StudyPlanTaskService(
            repository, accounts, new StudyToolService(), settings, rewards, progress, plans);

    private Map<String, Object> plan(String status) {
        return Map.of("id", 4L, "status", status, "subject", "数学");
    }

    @BeforeEach void signedIn() {
        when(accounts.id(auth)).thenReturn(7L);
        when(repository.findOwnedPlan(4, 7)).thenReturn(plan("active"));
        when(repository.findTask(8, 4)).thenReturn(Map.of("status", "pending"));
        when(repository.childCount(8)).thenReturn(Map.of("count", 0L));
        when(repository.taskReward(7, 8)).thenReturn(null);
        when(repository.activeSubjectGoal(7, "数学")).thenReturn(null);
        when(repository.firstActiveGoal(7)).thenReturn(null);
        when(settings.settings()).thenReturn(Map.of());
        when(settings.setting(anyMap(), anyString(), anyInt())).thenAnswer(call -> call.getArgument(2));
        when(plans.plan(auth, 4)).thenAnswer(call -> new LinkedHashMap<>(Map.of("id", 4L, "tasks", List.of())));
    }

    private void error(HttpStatus status, Runnable action) {
        assertEquals(status, assertThrows(ResponseStatusException.class, action::run).getStatusCode());
    }

    @Test void foreignPlanIsHiddenForEveryTaskWrite() {
        when(repository.findOwnedPlan(4, 7)).thenReturn(null);
        error(HttpStatus.NOT_FOUND, () -> tasks.addTask(auth, 4, Map.of("title", "任务")));
        error(HttpStatus.NOT_FOUND, () -> tasks.updateTask(auth, 4, 8, Map.of("status", "done")));
        error(HttpStatus.NOT_FOUND, () -> tasks.deleteTask(auth, 4, 8));
        verify(repository, times(3)).findOwnedPlan(4, 7);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(progress, rewards, plans);
    }

    @Test void taskFromAnotherPlanIsHiddenBeforeAnyMutation() {
        when(repository.findTask(8, 4)).thenReturn(null);
        error(HttpStatus.NOT_FOUND, () -> tasks.updateTask(auth, 4, 8, Map.of("title", "修改")));
        error(HttpStatus.NOT_FOUND, () -> tasks.deleteTask(auth, 4, 8));
        verify(repository, never()).updateTaskTitle(anyString(), anyLong());
        verify(repository, never()).deleteTask(anyLong());
        verifyNoInteractions(progress, rewards, plans);
    }

    @Test void addingAChildRollsUpTheParentBeforeReturningPlanDetail() {
        when(repository.findParentTask(8L, 4)).thenReturn(Map.of("id", 8L));
        when(repository.nextTaskOrder(4)).thenReturn(Map.of("next", 3L));
        var result = tasks.addTask(auth, 4, Map.of("title", " 子任务 ", "parentId", 8, "dueDate", "2026-11-08"));
        var order = inOrder(repository, progress, plans);
        order.verify(repository).findOwnedPlan(4, 7);
        order.verify(repository).findParentTask(8L, 4);
        order.verify(repository).nextTaskOrder(4);
        order.verify(repository).insertChildTask(4, 8L, "子任务", LocalDate.of(2026, 11, 8), 3);
        order.verify(progress).rollupTask(8);
        order.verify(progress).syncPlanStatus(4);
        order.verify(plans).plan(auth, 4);
        assertEquals(4L, result.get("id"));
    }

    @Test void aNestedChildCannotBecomeAnotherParent() {
        when(repository.findParentTask(8L, 4)).thenReturn(Map.of("parent_id", 3L));
        error(HttpStatus.BAD_REQUEST, () -> tasks.addTask(auth, 4, Map.of("title", "孙任务", "parentId", 8)));
        verify(repository, never()).insertChildTask(anyLong(), any(), anyString(), any(), anyLong());
        verifyNoInteractions(progress, plans);
    }

    @Test void parentStatusCannotBeChangedEvenWhenTitleIsInTheSameRequest() {
        when(repository.childCount(8)).thenReturn(Map.of("count", 1L));
        error(HttpStatus.BAD_REQUEST, () -> tasks.updateTask(auth, 4, 8, Map.of("title", "新标题", "status", "done")));
        verify(repository, never()).updateTaskTitle(anyString(), anyLong());
        verify(repository, never()).updateTaskStatus(anyString(), any(), anyLong());
        verifyNoInteractions(progress, rewards, plans);
    }

    @Test void completingAChildAwardsTaskAndFirstPlanCompletionAndRollsUp() {
        when(repository.findTask(8, 4)).thenReturn(Map.of("status", "pending", "parent_id", 3L));
        when(repository.findOwnedPlan(4, 7)).thenReturn(plan("active"), plan("completed"));
        when(repository.planReward(7, 4)).thenReturn(null);
        when(rewards.award(7, "plan_task", 1, "study_task", 8L, 10)).thenReturn(List.of(Map.of("code", "task")));
        when(rewards.award(7, "plan_complete", 10, "study_plan", 4L, 0)).thenReturn(List.of(Map.of("code", "plan")));
        var result = tasks.updateTask(auth, 4, 8, Map.of("status", "done"));
        assertEquals(11, result.get("pointsAwarded"));
        assertEquals(List.of(Map.of("code", "task"), Map.of("code", "plan")), result.get("badges"));
        assertNull(result.get("checkin"));
        verify(repository).updateTaskStatus(eq("done"), notNull(), eq(8L));
        var order = inOrder(progress, rewards);
        order.verify(rewards).award(7, "plan_task", 1, "study_task", 8L, 10);
        order.verify(progress).rollupTask(3);
        order.verify(progress).syncPlanStatus(4);
        order.verify(rewards).award(7, "plan_complete", 10, "study_plan", 4L, 0);
    }

    @Test void identicalCompletionDoesNotRewardOrCheckInAgain() {
        when(repository.findTask(8, 4)).thenReturn(Map.of("status", "done"));
        when(repository.findOwnedPlan(4, 7)).thenReturn(plan("completed"));
        var result = tasks.updateTask(auth, 4, 8, Map.of("status", "done", "checkin", true));
        assertEquals(0, result.get("pointsAwarded"));
        assertNull(result.get("checkin"));
        verifyNoInteractions(rewards, settings);
        verify(repository, never()).updateTaskStatus(anyString(), any(), anyLong());
        verify(repository, never()).activeSubjectGoal(anyLong(), anyString());
        verify(progress).syncPlanStatus(4);
    }

    @Test void reopenedTaskCannotAwardItsPointsAgainButCanStillRequestACheckin() {
        when(repository.taskReward(7, 8)).thenReturn(Map.of("exists", 1));
        var result = tasks.updateTask(auth, 4, 8, Map.of("status", "done", "checkin", true));
        assertEquals(0, result.get("pointsAwarded"));
        verifyNoInteractions(rewards, settings);
        verify(repository).updateTaskStatus(eq("done"), notNull(), eq(8L));
        verify(repository).activeSubjectGoal(7, "数学");
        verify(progress).syncPlanStatus(4);
    }

    @Test void recordedPlanRewardPreventsAnotherCompletionBonus() {
        when(repository.findOwnedPlan(4, 7)).thenReturn(plan("active"), plan("completed"));
        when(repository.planReward(7, 4)).thenReturn(Map.of("exists", 1));
        var result = tasks.updateTask(auth, 4, 8, Map.of("status", "done"));
        assertEquals(1, result.get("pointsAwarded"));
        verify(rewards).award(7, "plan_task", 1, "study_task", 8L, 10);
        verify(rewards, never()).award(eq(7L), eq("plan_complete"), anyInt(), anyString(), anyLong(), anyInt());
    }

    @Test void optionalCheckinKeepsGoalPrivacyAndPlanTaskReferences() {
        when(repository.activeSubjectGoal(7, "数学")).thenReturn(Map.of("id", 9L, "target_minutes", 25, "visibility", "private"));
        when(repository.todayCheckinCount(9)).thenReturn(Map.of("count", 0L));
        when(repository.todayCheckin(9)).thenReturn(Map.of("id", 12L));
        var result = tasks.updateTask(auth, 4, 8, Map.of("status", "done", "checkin", "true"));
        assertEquals(Map.of("id", 12L), result.get("checkin"));
        verify(repository).insertPlanCheckin(7, 9, "完成计划任务自动打卡", 25, "数学", "private", 4, 8);
        verify(repository, never()).firstActiveGoal(anyLong());
    }

    @Test void fallbackGoalDoesNotCreateASecondDailyCheckin() {
        when(repository.firstActiveGoal(7)).thenReturn(Map.of("id", 9L));
        when(repository.todayCheckinCount(9)).thenReturn(Map.of("count", 1L));
        assertNull(tasks.updateTask(auth, 4, 8, Map.of("status", "done", "checkin", true)).get("checkin"));
        verify(repository).firstActiveGoal(7);
        verify(repository, never()).insertPlanCheckin(anyLong(), anyLong(), anyString(), anyInt(), anyString(), anyString(), anyLong(), anyLong());
    }

    @Test void missingGoalDoesNotInterruptTaskCompletion() {
        assertNull(tasks.updateTask(auth, 4, 8, Map.of("status", "done", "checkin", true)).get("checkin"));
        verify(repository).firstActiveGoal(7);
        verify(progress).syncPlanStatus(4);
        verify(plans).plan(auth, 4);
    }

    @Test void reopeningClearsCompletionTimeWithoutAwardingOrCheckingIn() {
        when(repository.findTask(8, 4)).thenReturn(Map.of("status", "done", "parent_id", 3L));
        tasks.updateTask(auth, 4, 8, Map.of("status", "pending", "checkin", true));
        verify(repository).updateTaskStatus("pending", null, 8);
        verify(progress).rollupTask(3);
        verify(progress).syncPlanStatus(4);
        verifyNoInteractions(rewards, settings);
        verify(repository, never()).activeSubjectGoal(anyLong(), anyString());
    }

    @Test void deletingAChildRefreshesParentAndPlanBeforeReadingTheResponse() {
        when(repository.findTask(8, 4)).thenReturn(Map.of("parent_id", 3L));
        assertEquals(4L, tasks.deleteTask(auth, 4, 8).get("id"));
        var order = inOrder(repository, progress, plans);
        order.verify(repository).findOwnedPlan(4, 7);
        order.verify(repository).findTask(8, 4);
        order.verify(repository).deleteTask(8);
        order.verify(progress).rollupTask(3);
        order.verify(progress).syncPlanStatus(4);
        order.verify(plans).plan(auth, 4);
    }

    @Test void unwritableAccountCannotReachAnyTaskOperation() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(accounts).writable(auth);
        error(HttpStatus.FORBIDDEN, () -> tasks.addTask(auth, 4, Map.of("title", "任务")));
        error(HttpStatus.FORBIDDEN, () -> tasks.updateTask(auth, 4, 8, Map.of("status", "done")));
        error(HttpStatus.FORBIDDEN, () -> tasks.deleteTask(auth, 4, 8));
        verifyNoInteractions(repository, progress, rewards, plans);
    }
}
