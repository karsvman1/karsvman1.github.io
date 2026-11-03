package nl.japandi.dashboard;

/**
 * Display order for Google Tasks. Pure Java, unit-tested in android/test.
 *
 * Open tasks first: overdue, due today, without a due date, due later (by date). Tasks
 * completed today come last, so finished work stays visible but never pushes open work down.
 */
final class TaskOrder {
    static final int OVERDUE = 0, TODAY = 1, UNDATED = 2, LATER = 3, DONE = 4;

    private TaskOrder() {
    }

    /** @param due local midnight of the due date, or 0 without one */
    static int category(long due, long todayMidnight, boolean completed) {
        if (completed) return DONE;
        if (due == 0) return UNDATED;
        if (due < todayMidnight) return OVERDUE;
        if (due == todayMidnight) return TODAY;
        return LATER;
    }

    /** Comparator body: category, then due date, then title (case-insensitive). */
    static int compare(int catA, long dueA, String titleA, int catB, long dueB, String titleB) {
        if (catA != catB) return catA < catB ? -1 : 1;
        if (dueA != dueB) return dueA < dueB ? -1 : 1;
        return titleA.compareToIgnoreCase(titleB);
    }
}
