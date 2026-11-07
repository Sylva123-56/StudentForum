package com.studentforum.service;

import com.studentforum.repository.StudyPlanRepository;
import java.time.LocalDate;
import java.util.Arrays;
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

class StudyPlanTemplateServiceTest {
    private final StudyPlanRepository repository = mock(StudyPlanRepository.class);
    private final AccountAccessService accounts = mock(AccountAccessService.class);
    private final StudyPreferencesService preferences = mock(StudyPreferencesService.class);
    private final StudyPlanProgressService progress = mock(StudyPlanProgressService.class);
    private final Authentication auth = mock(Authentication.class);
    private final StudyPlanTemplateService templates = new StudyPlanTemplateService(
            repository, accounts, new StudyToolService(), preferences, progress);

    @BeforeEach void signedIn() {
        when(accounts.id(auth)).thenReturn(7L);
    }

    private void error(HttpStatus status, Runnable action) {
        assertEquals(status, assertThrows(ResponseStatusException.class, action::run).getStatusCode());
    }

    @Test void listKeepsOfficialMineSharedAndSubjects() {
        when(repository.officialTemplates("数学")).thenReturn(List.of(Map.of("id", 1L)));
        when(repository.ownedTemplates(7, "数学")).thenReturn(List.of(Map.of("id", 2L)));
        when(repository.sharedTemplates(7, "数学")).thenReturn(List.of(Map.of("id", 3L)));
        when(preferences.subjects(7)).thenReturn(List.of("数学"));
        assertEquals(Map.of("official", List.of(Map.of("id", 1L)), "mine", List.of(Map.of("id", 2L)),
                "shared", List.of(Map.of("id", 3L)), "subjects", List.of("数学")), templates.templates(auth, "数学"));
    }

    @Test void createNormalizesTasksAndDefaultsWithoutChangingTheStoredShape() {
        when(repository.insertTemplate(7, "复习模板", "", "", 7, "第一题\n" + "长".repeat(100), "shared")).thenReturn(8L);
        when(repository.findTemplate(8)).thenReturn(Map.of("id", 8L));
        assertEquals(Map.of("id", 8L), templates.createTemplate(auth,
                Map.of("title", "复习模板", "tasks", Arrays.asList(null, " ", " 第一题 ", "长".repeat(101)))));
        verify(accounts).writable(auth);
    }

    @Test void invalidCycleNeverWrites() {
        error(HttpStatus.BAD_REQUEST, () -> templates.createTemplate(auth,
                Map.of("title", "复习模板", "cycleDays", 0, "tasks", List.of("题目"))));
        verifyNoInteractions(repository);
    }

    @Test void invalidVisibilityNeverWrites() {
        error(HttpStatus.BAD_REQUEST, () -> templates.createTemplate(auth,
                Map.of("title", "复习模板", "visibility", "public", "tasks", List.of("题目"))));
        verifyNoInteractions(repository);
    }

    @Test void emptyTasksNeverWrite() {
        error(HttpStatus.BAD_REQUEST, () -> templates.createTemplate(auth,
                Map.of("title", "复习模板", "tasks", List.of(" "))));
        verifyNoInteractions(repository);
    }

    @Test void savingAPlanKeepsOnlyRootTasksAndRelativeDays() {
        LocalDate start = LocalDate.of(2026, 11, 7);
        when(repository.findOwnedPlan(4, 7)).thenReturn(Map.of("title", "复习计划", "subject", "数学", "goal", "提高", "start_date", start));
        when(progress.planTasks(4)).thenReturn(List.of(Map.of("title", "概念", "due_date", start.minusDays(1)),
                Map.of("title", "子任务", "parent_id", 11L), Map.of("title", "练习", "due_date", start.plusDays(2))));
        when(repository.insertTemplate(7, "复习计划", "数学", "提高", 7, "概念|第 1 天\n练习|第 3 天", "shared")).thenReturn(8L);
        when(repository.findTemplate(8)).thenReturn(Map.of("id", 8L));
        assertEquals(Map.of("id", 8L), templates.templateFromPlan(auth, 4, null));
    }

    @Test void foreignPlanIsHiddenBeforeItsTasksAreRead() {
        when(repository.findOwnedPlan(4, 7)).thenReturn(null);
        error(HttpStatus.NOT_FOUND, () -> templates.templateFromPlan(auth, 4, null));
        verify(repository).findOwnedPlan(4, 7);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(progress);
    }

    @Test void planWithoutRootTasksCannotBeSaved() {
        when(repository.findOwnedPlan(4, 7)).thenReturn(Map.of("title", "复习计划"));
        when(progress.planTasks(4)).thenReturn(List.of(Map.of("title", "孤立子任务", "parent_id", 11L)));
        error(HttpStatus.BAD_REQUEST, () -> templates.templateFromPlan(auth, 4, null));
        verify(repository, never()).insertTemplate(anyLong(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyString());
    }

    @Test void onlyOwnerCanDeleteAndOfficialTemplatesCannotBeDeleted() {
        when(repository.findTemplate(8)).thenReturn(Map.of("owner_id", 9L));
        error(HttpStatus.FORBIDDEN, () -> templates.deleteTemplate(auth, 8));
        when(repository.findTemplate(8)).thenReturn(Map.of("is_official", true));
        error(HttpStatus.FORBIDDEN, () -> templates.deleteTemplate(auth, 8));
        verify(repository, never()).deleteTemplate(anyLong());
        when(repository.findTemplate(8)).thenReturn(Map.of("owner_id", 7L));
        assertEquals(Map.of("deleted", true), templates.deleteTemplate(auth, 8));
        verify(repository).deleteTemplate(8);
    }

    @Test void missingTemplateRetainsNotFound() {
        when(repository.findTemplate(8)).thenReturn(null);
        error(HttpStatus.NOT_FOUND, () -> templates.deleteTemplate(auth, 8));
        error(HttpStatus.NOT_FOUND, () -> templates.readableTemplate(8, 7));
    }

    @Test void taskExpansionKeepsOrderClampsDatesAndAcceptsUnscheduledLines() {
        LocalDate start = LocalDate.of(2026, 11, 7), end = start.plusDays(1);
        when(repository.findTemplate(8)).thenReturn(Map.of("owner_id", 7L, "visibility", "private",
                "tasks", "  第一题|第 1 天\r\n\n第二题|第 9 天\n第三题|第 无效 天\n" + "长".repeat(101)));
        templates.applyTasks(4, 8, start, end, 7);
        var order = inOrder(repository);
        order.verify(repository).findTemplate(8);
        order.verify(repository).insertTask(4, "第一题", start, 0);
        order.verify(repository).insertTask(4, "第二题", end, 1);
        order.verify(repository).insertTask(4, "第三题", null, 2);
        order.verify(repository).insertTask(4, "长".repeat(100), null, 3);
        order.verifyNoMoreInteractions();
    }

    @Test void bothTaskExpansionAndDirectReadRejectForeignPrivateTemplates() {
        when(repository.findTemplate(8)).thenReturn(Map.of("owner_id", 9L, "visibility", "private"));
        error(HttpStatus.BAD_REQUEST, () -> templates.readableTemplate(8, 7));
        error(HttpStatus.BAD_REQUEST, () -> templates.applyTasks(4, 8, LocalDate.now(), null, 7));
        verify(repository, never()).insertTask(anyLong(), anyString(), any(), anyInt());
        when(repository.findTemplate(8)).thenReturn(Map.of("owner_id", 9L, "visibility", "shared"));
        assertNotNull(templates.readableTemplate(8, 7));
        when(repository.findTemplate(8)).thenReturn(new LinkedHashMap<>());
        assertNotNull(templates.readableTemplate(8, 7));
    }

    @Test void unwritableAccountIsRejectedBeforeAnyTemplateQueryOrWrite() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "账号不可写")).when(accounts).writable(auth);
        error(HttpStatus.FORBIDDEN, () -> templates.createTemplate(auth, Map.of()));
        error(HttpStatus.FORBIDDEN, () -> templates.templateFromPlan(auth, 4, null));
        error(HttpStatus.FORBIDDEN, () -> templates.deleteTemplate(auth, 8));
        verifyNoInteractions(repository, progress);
    }
}
