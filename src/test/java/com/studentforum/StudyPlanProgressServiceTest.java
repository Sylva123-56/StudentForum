package com.studentforum;

import com.studentforum.repository.StudyPlanRepository;
import com.studentforum.service.StudyPlanProgressService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudyPlanProgressServiceTest {
    private final StudyPlanRepository repository = mock(StudyPlanRepository.class);
    private final StudyPlanProgressService progress = new StudyPlanProgressService(repository);

    @Test void countsOnlyTopLevelTasksAndKeepsPercentageShape() {
        when(repository.progress(4)).thenReturn(List.of(Map.of("total", 3L, "done", 2L,
                "done_today", 1L, "overdue", 0L)));
        assertEquals(Map.of("total", 3L, "done", 2L, "percent", 67L, "doneToday", 1L, "overdue", 0L), progress.progress(4));
    }

    @Test void parentRollupCannotTreatLeafOrMissingTaskAsParent() {
        when(repository.parent(9)).thenReturn(Map.of("id", 9L, "parent_id", 8L));
        progress.rollupTask(9);
        progress.rollupTask(10);
        verify(repository, never()).updateParent(anyLong(), anyString(), any());
    }

    @Test void archivedPlanIsNeverReopenedByTaskProgress() {
        when(repository.planStatus(4)).thenReturn(Map.of("status", "archived"));
        progress.syncPlanStatus(4);
        verify(repository, never()).progress(4);
        verify(repository, never()).updatePlanStatus(anyLong(), anyString());
    }

    @Test void completionAndUncheckRespectStoredStatus() {
        when(repository.planStatus(4)).thenReturn(Map.of("status", "active"));
        when(repository.progress(4)).thenReturn(List.of(Map.of("total", 2L, "done", 2L)));
        progress.syncPlanStatus(4);
        verify(repository).updatePlanStatus(4, "completed");
        when(repository.planStatus(4)).thenReturn(Map.of("status", "completed"));
        when(repository.progress(4)).thenReturn(List.of(Map.of("total", 2L, "done", 1L)));
        progress.syncPlanStatus(4);
        verify(repository).updatePlanStatus(4, "active");
    }
}
