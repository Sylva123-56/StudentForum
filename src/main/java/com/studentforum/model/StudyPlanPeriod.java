package com.studentforum.model;

import java.time.LocalDate;

/** A plan may omit an end date, but it cannot end before it starts. */
public final class StudyPlanPeriod {
    private final LocalDate start;
    private final LocalDate end;

    public StudyPlanPeriod(LocalDate start, LocalDate end) {
        this.start = start;
        this.end = end;
    }

    public boolean isValid() {
        return start == null || end == null || !end.isBefore(start);
    }
}
