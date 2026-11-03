package nl.japandi.dashboard;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Birthday rules for events in the Google Calendar named "Verjaardagen". Pure Java, unit-tested
 * in android/test.
 *
 * An event counts as a birthday only when it is an all-day event that repeats yearly (RRULE
 * FREQ=YEARLY on its series) or that Google marks with eventType "birthday". Other events in that
 * calendar are ignored, so a one-off note there never shows as a birthday.
 *
 * The age is shown only when a birth year is written down explicitly: "Anna (1990)" or
 * "Anna *1990" in the title, or "geboren 1990" / "geboortejaar: 1990" in the description. The
 * start year of the recurring series is NOT used: people often create the series this year.
 */
final class Birthdays {
    static final String CALENDAR_NAME = "Verjaardagen";
    private static final long DAY_MS = 24L * 3600 * 1000;
    private static final Pattern YEAR_IN_TITLE = Pattern.compile("(?:\\(|\\*\\s?)((?:19|20)\\d\\d)\\)?");
    private static final Pattern YEAR_IN_DESC = Pattern.compile(
            "(?i)(?:geboren|geboortejaar|born)\\D{0,3}((?:19|20)\\d\\d)");
    private static final Pattern NOISE = Pattern.compile(
            "(?i)(\\(?\\*?\\s?\\b(?:19|20)\\d\\d\\b\\)?|\\bverjaardag van\\b|'s verjaardag\\b"
            + "|\\bverjaardag\\b|\\bbirthday\\b|\\bjarig\\b)");  // whole words only

    private Birthdays() {
    }

    /** True for a recurrence list (Google "recurrence" array) that repeats yearly. */
    static boolean isYearly(String[] recurrence) {
        if (recurrence == null) return false;
        for (int i = 0; i < recurrence.length && i < 20; i++) {
            String r = recurrence[i] == null ? "" : recurrence[i].toUpperCase(Locale.US);
            if (r.startsWith("RRULE:") && r.contains("FREQ=YEARLY")) return true;
        }
        return false;
    }

    static boolean isBirthday(boolean allDay, boolean yearly, String eventType) {
        return allDay && (yearly || "birthday".equals(eventType));
    }

    /** The person's name from the event title, without year and words like "verjaardag". */
    static String name(String summary) {
        if (summary == null) return "";
        String n = NOISE.matcher(summary).replaceAll(" ");
        n = n.replaceAll("\\s+", " ").trim();
        // Strip leftover separators at either end, e.g. "Anna -" or ": Piet".
        n = n.replaceAll("^[\\s\\-:,.]+|[\\s\\-:,.]+$", "");
        return n.isEmpty() ? summary.trim() : n;
    }

    /** Explicit birth year, or 0 when none is written down or it is not plausible. */
    static int birthYear(String summary, String description, int maxYear) {
        int y = find(YEAR_IN_TITLE, summary);
        if (y == 0) y = find(YEAR_IN_DESC, description);
        return y >= 1900 && y <= maxYear ? y : 0;
    }

    private static int find(Pattern p, String text) {
        if (text == null) return 0;
        Matcher m = p.matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** Age reached on that birthday, or -1 when the birth year is unknown or later. */
    static int age(int birthYear, int occurrenceYear) {
        if (birthYear <= 0 || birthYear > occurrenceYear) return -1;
        return occurrenceYear - birthYear;
    }

    /**
     * Whole days from one local midnight to another. Rounding absorbs the 23- or 25-hour days
     * at daylight-saving changes.
     */
    static int daysUntil(long todayMidnight, long dateMidnight) {
        return (int) Math.round((dateMidnight - todayMidnight) / (double) DAY_MS);
    }
}
