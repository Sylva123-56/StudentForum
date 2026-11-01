package com.studentforum;

import com.studentforum.model.ModerationScope;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModerationScopeTest {
    @Test void adminCanSeeAllBoards() {
        ModerationScope scope = new ModerationScope("admin", null);
        assertNull(scope.postBoards());
        assertNull(scope.reportBoard());
    }

    @Test void moderatorOnlySeesTheirOwnBoard() {
        ModerationScope scope = new ModerationScope("moderator", 12L);
        assertEquals(List.of(12L), scope.postBoards());
        assertEquals(12L, scope.reportBoard());
    }

    @Test void unassignedModeratorMustNotSeeAnyBoard() {
        ModerationScope scope = new ModerationScope("moderator", null);
        assertEquals(List.of(-1L), scope.postBoards());
        assertEquals(-1L, scope.reportBoard());
    }
}
