package nl.japandi.dashboard;

import java.util.TimeZone;

/**
 * Plain-JVM tests for TimeUtil (no JUnit available offline). Run via android/test/run.sh.
 * Exits non-zero on the first failure.
 */
public final class TimeUtilTest {
    private static final TimeZone AMS = TimeZone.getTimeZone("Europe/Amsterdam");
    private static int checks;

    private TimeUtilTest() {
    }

    private static void eq(String what, long expected, long actual) {
        checks++;
        if (expected != actual) {
            System.out.println("FAIL " + what + ": expected " + expected + " got " + actual);
            System.exit(1);
        }
    }

    private static void eq(String what, String expected, String actual) {
        checks++;
        if (!expected.equals(actual)) {
            System.out.println("FAIL " + what + ": expected " + expected + " got " + actual);
            System.exit(1);
        }
    }

    public static void main(String[] args) {
        long t0800 = 1793689200000L;  // 2026-11-03T07:00:00Z = 08:00 Amsterdam (CET)
        eq("offset +01:00", t0800, TimeUtil.parseIso("2026-11-03T08:00:00+01:00", AMS));
        eq("Z", t0800, TimeUtil.parseIso("2026-11-03T07:00:00Z", AMS));
        eq("fraction + Z", t0800 + 0, TimeUtil.parseIso("2026-11-03T07:00:00.000Z", AMS));
        eq("negative offset", t0800, TimeUtil.parseIso("2026-11-03T02:00:00-05:00", AMS));
        eq("no offset = local", t0800, TimeUtil.parseIso("2026-11-03T08:00:00", AMS));
        eq("garbage", 0, TimeUtil.parseIso("not a date at all!!", AMS));
        eq("bad offset", 0, TimeUtil.parseIso("2026-11-03T08:00:00+0100", AMS));
        eq("null", 0, TimeUtil.parseIso(null, AMS));

        // DST end in Europe: 2026-10-25 lasts 25 hours. "Tomorrow" must still be local midnight.
        long oct25 = TimeUtil.parseDate("2026-10-25", AMS);
        long oct26 = TimeUtil.parseDate("2026-10-26", AMS);
        eq("25-hour day", 25L * 3600 * 1000, oct26 - oct25);
        eq("addDays over DST end", oct26, TimeUtil.addDays(oct25 + 12 * 3600 * 1000L, 1, AMS));
        eq("startOfDay late evening", oct25, TimeUtil.startOfDay(oct25 + 24 * 3600 * 1000L, AMS));
        // DST start: 2026-03-29 lasts 23 hours.
        long mar29 = TimeUtil.parseDate("2026-03-29", AMS);
        eq("23-hour day", 23L * 3600 * 1000, TimeUtil.addDays(mar29, 1, AMS) - mar29);

        eq("formatLocal winter", "2026-11-03T08:00:00+01:00", TimeUtil.formatLocal(t0800, AMS));
        eq("formatLocal summer", "2026-07-01T12:00:00+02:00",
                TimeUtil.formatLocal(TimeUtil.parseIso("2026-07-01T10:00:00Z", AMS), AMS));
        eq("formatUtc", "2026-11-03T07:00:00Z", TimeUtil.formatUtc(t0800));
        eq("round trip", t0800, TimeUtil.parseIso(TimeUtil.formatLocal(t0800, AMS), AMS));
        eq("bad date", 0, TimeUtil.parseDate("2026/11/03", AMS));
        // Review 2: impossible dates are rejected instead of rolling over (lenient Calendar).
        eq("30 February", 0, TimeUtil.parseDate("2026-02-30", AMS));
        eq("month 13", 0, TimeUtil.parseDate("2026-13-01", AMS));
        eq("hour 25", 0, TimeUtil.parseIso("2026-11-03T25:00:00Z", AMS));
        eq("leap day ok", TimeUtil.addDays(TimeUtil.parseDate("2028-02-28", AMS), 1, AMS),
                TimeUtil.parseDate("2028-02-29", AMS));

        System.out.println("OK " + checks + " checks");
    }
}
