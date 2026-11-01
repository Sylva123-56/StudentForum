package com.studentforum;

import com.studentforum.model.Post;
import com.studentforum.model.StudyPlanPeriod;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PostAndPlanModelTest {
    @Test void postPermissionsDependOnOwnerTypeAndStatus() {
        Post question = new Post(7L, "question", "published");
        assertTrue(question.editableBy(7L));
        assertTrue(question.acceptsAnswerFrom(7L));
        assertFalse(question.acceptsAnswerFrom(8L));
        assertFalse(new Post(7L, "discussion", "published").acceptsAnswerFrom(7L));
        assertFalse(new Post(7L, "question", "hidden").editableBy(7L));
        assertTrue(new Post(7L, "question", "hidden").ownedBy(7L));
    }

    @Test void planPeriodAllowsOpenEndedPlansButRejectsReversedDates() {
        LocalDate start = LocalDate.of(2026, 1, 10);
        assertTrue(new StudyPlanPeriod(start, null).isValid());
        assertTrue(new StudyPlanPeriod(start, start).isValid());
        assertFalse(new StudyPlanPeriod(start, start.minusDays(1)).isValid());
    }
}
