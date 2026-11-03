package nl.japandi.dashboard;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Date/time helpers without Android dependencies, so they can be unit-tested on a normal JVM
 * (see android/test). java.time is unavailable before Android API 26.
 *
 * Day boundaries are computed with Calendar arithmetic, never as "+ 24 hours": on daylight
 * saving transitions a local day lasts 23 or 25 hours.
 */
final class TimeUtil {
    private TimeUtil() {
    }

    /**
     * Parses ISO-8601 "yyyy-MM-ddTHH:mm:ss" with an optional fraction and a "Z" or "+HH:MM"
     * offset. Without offset the time is taken as local time in {@code local}.
     *
     * @return epoch milliseconds, or 0 when the text is not in this format
     */
    static long parseIso(String t, TimeZone local) {
        if (t == null || t.length() < 19 || t.charAt(4) != '-' || t.charAt(10) != 'T') return 0;
        try {
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
            c.clear();
            c.setLenient(false);  // reject e.g. hour 25 instead of rolling over
            c.set(Integer.parseInt(t.substring(0, 4)), Integer.parseInt(t.substring(5, 7)) - 1,
                    Integer.parseInt(t.substring(8, 10)), Integer.parseInt(t.substring(11, 13)),
                    Integer.parseInt(t.substring(14, 16)), Integer.parseInt(t.substring(17, 19)));
            long ms = c.getTimeInMillis();
            String zone = t.substring(19);
            if (zone.startsWith(".")) {  // skip fractional seconds (bounded: at most 9 digits)
                int i = 1;
                while (i < zone.length() && i < 10 && Character.isDigit(zone.charAt(i))) i++;
                zone = zone.substring(i);
            }
            if (zone.equals("Z")) return ms;
            if (zone.isEmpty()) return ms - local.getOffset(ms - local.getRawOffset());
            if (zone.length() != 6 || zone.charAt(3) != ':' || (zone.charAt(0) != '+' && zone.charAt(0) != '-')) {
                return 0;
            }
            int sign = zone.charAt(0) == '-' ? -1 : 1;
            int oh = Integer.parseInt(zone.substring(1, 3));
            int om = Integer.parseInt(zone.substring(4, 6));
            return ms - sign * (oh * 3600000L + om * 60000L);
        } catch (IllegalArgumentException e) {  // bad digits or impossible fields (non-lenient)
            return 0;
        }
    }

    /** Local midnight at the start of the day containing {@code ms}. */
    static long startOfDay(long ms, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz, Locale.US);
        c.setTimeInMillis(ms);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Local midnight {@code days} calendar days after the day containing {@code ms}. */
    static long addDays(long ms, int days, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz, Locale.US);
        c.setTimeInMillis(startOfDay(ms, tz));
        c.add(Calendar.DAY_OF_MONTH, days);
        return c.getTimeInMillis();
    }

    /** "2026-11-03" as local midnight, or 0 when the text is not a date. */
    static long parseDate(String d, TimeZone tz) {
        if (d == null || d.length() < 10 || d.charAt(4) != '-' || d.charAt(7) != '-') return 0;
        try {
            Calendar c = Calendar.getInstance(tz, Locale.US);
            c.clear();
            c.setLenient(false);  // reject e.g. 2026-02-30 instead of rolling over to 2 March
            c.set(Integer.parseInt(d.substring(0, 4)), Integer.parseInt(d.substring(5, 7)) - 1,
                    Integer.parseInt(d.substring(8, 10)));
            return c.getTimeInMillis();
        } catch (IllegalArgumentException e) {  // bad digits or impossible fields (non-lenient)
            return 0;
        }
    }

    /** Local time with numeric offset, e.g. 2026-11-03T15:20:00+01:00. */
    static String formatLocal(long ms, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz, Locale.US);
        c.setTimeInMillis(ms);
        int off = tz.getOffset(ms) / 60000;
        char sign = off < 0 ? '-' : '+';
        off = Math.abs(off);
        return String.format(Locale.US, "%04d-%02d-%02dT%02d:%02d:%02d%c%02d:%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND),
                sign, off / 60, off % 60);
    }

    /** UTC "yyyy-MM-ddTHH:mm:ssZ" for Google API query parameters. */
    static String formatUtc(long ms) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        c.setTimeInMillis(ms);
        return String.format(Locale.US, "%04d-%02d-%02dT%02d:%02d:%02dZ",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND));
    }
}
