package nl.japandi.dashboard;

import java.util.List;
import java.util.TimeZone;

/**
 * Plain-JVM tests for the pure logic behind the redesigned screen: birthdays, task order,
 * paging and the pixel font. Run via android/test/run.sh. Exits non-zero on the first failure.
 */
public final class LogicTest {
    private static final TimeZone AMS = TimeZone.getTimeZone("Europe/Amsterdam");
    private static int checks;

    private LogicTest() {
    }

    private static void eq(String what, Object expected, Object actual) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            System.out.println("FAIL " + what + ": expected " + expected + " got " + actual);
            System.exit(1);
        }
    }

    public static void main(String[] args) {
        birthdays();
        taskOrder();
        paging();
        font();
        System.out.println("OK " + checks + " checks");
    }

    private static void birthdays() {
        eq("yearly rule", true, Birthdays.isYearly(new String[]{"RRULE:FREQ=YEARLY"}));
        eq("yearly with extras", true, Birthdays.isYearly(new String[]{"EXDATE:20250101", "RRULE:FREQ=YEARLY;BYMONTH=3"}));
        eq("weekly is not yearly", false, Birthdays.isYearly(new String[]{"RRULE:FREQ=WEEKLY"}));
        eq("no recurrence", false, Birthdays.isYearly(null));
        eq("all-day yearly is birthday", true, Birthdays.isBirthday(true, true, "default"));
        eq("timed yearly is not", false, Birthdays.isBirthday(false, true, "default"));
        eq("one-off all-day is not", false, Birthdays.isBirthday(true, false, "default"));
        eq("eventType birthday", true, Birthdays.isBirthday(true, false, "birthday"));

        eq("plain name", "Anna", Birthdays.name("Anna"));
        eq("year stripped", "Anna", Birthdays.name("Anna (1990)"));
        eq("star year stripped", "Piet", Birthdays.name("Piet *1985"));
        eq("verjaardag van", "Oma", Birthdays.name("Verjaardag van Oma"));
        eq("'s verjaardag", "Kees", Birthdays.name("Kees's verjaardag"));
        eq("separator", "Lisa", Birthdays.name("Lisa - jarig"));
        eq("only noise keeps title", "Verjaardag", Birthdays.name("Verjaardag"));
        // Review 2: words are only removed as whole words.
        eq("no partial word removal", "Tom Jarigsma", Birthdays.name("Tom Jarigsma"));
        eq("compound kept", "Verjaardagsfeest Tom", Birthdays.name("Verjaardagsfeest Tom"));

        eq("year in title", 1990, Birthdays.birthYear("Anna (1990)", "", 2026));
        eq("star year", 1985, Birthdays.birthYear("Piet *1985", null, 2026));
        eq("year in description", 2001, Birthdays.birthYear("Sam", "Geboren: 2001", 2026));
        eq("geboortejaar", 1970, Birthdays.birthYear("Jan", "geboortejaar 1970", 2026));
        eq("no year", 0, Birthdays.birthYear("Anna", "cadeau: boek", 2026));
        eq("future year rejected", 0, Birthdays.birthYear("Baby (2031)", "", 2026));
        eq("bare number not a year", 0, Birthdays.birthYear("Anna 1990", "", 2026));

        eq("age", 36, Birthdays.age(1990, 2026));
        eq("age unknown", -1, Birthdays.age(0, 2026));
        eq("age born later", -1, Birthdays.age(2027, 2026));

        long nov3 = TimeUtil.parseDate("2026-11-03", AMS);
        eq("today", 0, Birthdays.daysUntil(nov3, nov3));
        eq("tomorrow", 1, Birthdays.daysUntil(nov3, TimeUtil.parseDate("2026-11-04", AMS)));
        eq("over year end", 59, Birthdays.daysUntil(nov3, TimeUtil.parseDate("2027-01-01", AMS)));
        // Across the spring DST change (a 23-hour day) and the autumn one (25 hours).
        eq("over DST start", 2, Birthdays.daysUntil(TimeUtil.parseDate("2027-03-27", AMS),
                TimeUtil.parseDate("2027-03-29", AMS)));
        eq("over DST end", 365, Birthdays.daysUntil(nov3, TimeUtil.parseDate("2027-11-03", AMS)));
    }

    private static void taskOrder() {
        long today = TimeUtil.parseDate("2026-11-03", AMS);
        long yesterday = TimeUtil.parseDate("2026-11-02", AMS);
        long friday = TimeUtil.parseDate("2026-11-06", AMS);
        eq("overdue", TaskOrder.OVERDUE, TaskOrder.category(yesterday, today, false));
        eq("today", TaskOrder.TODAY, TaskOrder.category(today, today, false));
        eq("undated", TaskOrder.UNDATED, TaskOrder.category(0, today, false));
        eq("later", TaskOrder.LATER, TaskOrder.category(friday, today, false));
        eq("done wins", TaskOrder.DONE, TaskOrder.category(yesterday, today, true));
        eq("overdue before today", -1, TaskOrder.compare(0, yesterday, "b", 1, today, "a"));
        eq("same category by date", -1, TaskOrder.compare(3, today, "z", 3, friday, "a"));
        eq("same date by title", 1, TaskOrder.compare(2, 0, "Bram", 2, 0, "anna"));
    }

    private static void paging() {
        int[] one = Paging.pageStarts(new float[0], 100, null);
        eq("empty list has one page", 1, one.length);
        int[] p = Paging.pageStarts(new float[]{40, 40, 40, 40, 40}, 100, null);
        eq("pages", 3, p.length);
        eq("page 2 start", 2, p[1]);
        eq("page 3 start", 4, p[2]);
        eq("last page end", 5, Paging.pageEnd(p, 2, 5));
        int[] tall = Paging.pageStarts(new float[]{150, 30}, 100, null);
        eq("oversized row gets own page", 2, tall.length);
        // 25 tasks of 38 px in a 400 px panel: every row is on exactly one page.
        float[] h = new float[25];
        for (int i = 0; i < h.length; i++) h[i] = 38;
        int[] q = Paging.pageStarts(h, 400, null);
        int covered = 0;
        for (int k = 0; k < q.length; k++) covered += Paging.pageEnd(q, k, 25) - q[k];
        eq("no row lost", 25, covered);
        eq("ten per page", 3, q.length);
        // Review 2: the header (index 2) fits at the end of page 1 but its first row does not.
        // It must move along, and no page may hold more than fits (the earlier post-pass put
        // 30 + 50 + 50 = 130 px on a 110 px page).
        float[] hh = {40, 40, 30, 50, 50};
        int[] hp = Paging.pageStarts(hh, 110, new boolean[]{false, false, true, false, false});
        eq("header starts page 2", 2, hp[1]);
        boolean fits = true;
        for (int k = 0; k < hp.length; k++) {
            float used = 0;
            for (int i = hp[k]; i < Paging.pageEnd(hp, k, hh.length); i++) used += hh[i];
            fits &= used <= 110;
        }
        eq("every page fits", true, fits);
    }

    private static void font() {
        eq("digit width", 5, PixelFont.measure("8"));
        eq("clock width", 25, PixelFont.measure("88:88"));
        eq("space", 3, PixelFont.measure(" "));
        eq("all ascii letters known", true, allKnown("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"));
        List<Object> u = PixelFont.units("é");
        eq("accent composed", true, ((PixelFont.Unit) u.get(0)).glyph != null && ((PixelFont.Unit) u.get(0)).mark != null);
        eq("accent width cached", 5, PixelFont.measure("é"));
        eq("unknown char falls back", null, ((PixelFont.Unit) PixelFont.units("☺").get(0)).glyph);
        eq("en dash substituted", PixelFont.measure("-"), PixelFont.measure("–"));
        eq("ellipsis char = three dots", PixelFont.measure("..."), PixelFont.measure("…"));
        String e = PixelFont.ellipsize("Boodschappen doen bij de markt", 40);
        eq("ellipsized fits", true, PixelFont.measure(e) <= 40);
        eq("ellipsized marked", true, e.endsWith("..."));
        eq("too narrow for dots", "", PixelFont.ellipsize("Bellen", 3));
        List<String> lines = PixelFont.wrap("Belastingaangifte invullen en versturen voor 1 mei", 80, 2);
        eq("wrap max two lines", 2, lines.size());
        eq("wrapped lines fit", true, PixelFont.measure(lines.get(0)) <= 80 && PixelFont.measure(lines.get(1)) <= 80);
        eq("short text one line", 1, PixelFont.wrap("Bellen", 80, 2).size());
        List<String> word = PixelFont.wrap("Aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 40, 2);
        eq("long word cut", true, PixelFont.measure(word.get(0)) <= 40);

        // Review 2: an emoji (surrogate pair) is one fallback cell, not two broken halves;
        // variation selector U+FE0F is invisible.
        String cake = "a🎂️b";
        List<Object> emoji = PixelFont.units(cake);
        eq("emoji is one unit", 3, emoji.size());
        eq("emoji kept whole", "🎂", ((PixelFont.Unit) emoji.get(1)).fallback);
        eq("emoji width", 5 + 1 + 5 + 1 + 5, PixelFont.measure(cake));
        eq("fit never splits a pair", 1, PixelFont.fit("a🎂", 7));

        // Review 2: measuring and wrapping are linear; a 1920-character title stays fast.
        String longTitle = repeat("Lange titel ", 160);
        long t0 = System.nanoTime();
        List<String> lw = PixelFont.wrap(longTitle, 200, 2);
        eq("long wrap two lines", 2, lw.size());
        eq("long wrap fast (<200 ms)", true, System.nanoTime() - t0 < 200000000L);
        int end = PixelFont.fit(longTitle, 300);
        eq("fit is the longest prefix", true, PixelFont.measure(longTitle.substring(0, end)) <= 300
                && PixelFont.measure(longTitle.substring(0, end + 1)) > 300);
    }

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }

    private static boolean allKnown(String t) {
        for (int i = 0; i < t.length(); i++) if (!PixelFont.has(t.charAt(i))) return false;
        return true;
    }
}
