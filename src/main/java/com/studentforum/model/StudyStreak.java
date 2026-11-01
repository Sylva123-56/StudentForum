package com.studentforum.model;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** A study streak counts consecutive dates, including make-up check-ins. */
public final class StudyStreak {
    private final List<LocalDate> dates;
    private final LocalDate today;

    public StudyStreak(List<LocalDate> dates, LocalDate today) {
        this.dates = dates;
        this.today = today;
    }

    public int length() {
        if (dates == null || dates.isEmpty()) return 0;
        List<LocalDate> sorted = new ArrayList<>(dates);
        sorted.sort((left, right) -> right.compareTo(left));
        LocalDate first = sorted.getFirst();
        if (ChronoUnit.DAYS.between(first, today) > 1) return 0;
        int count = 1;
        LocalDate previous = first;
        for (int index = 1; index < sorted.size(); index++) {
            LocalDate current = sorted.get(index);
            if (ChronoUnit.DAYS.between(current, previous) == 1) {
                count++;
                previous = current;
            } else if (current.equals(previous)) {
                continue;
            } else {
                break;
            }
        }
        return count;
    }
}
