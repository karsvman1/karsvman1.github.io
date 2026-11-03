package nl.japandi.dashboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TimeZone;

/**
 * Retro handheld-style dashboard, drawn on one Canvas with the built-in pixel font and pixel
 * sprites: no widgets, no animation, one redraw per minute (plus one per tap).
 *
 * Landscape layout for the 1024 x 758 panel:
 * <pre>
 *  +- clock -----------+  +- VANDAAG ------------------- 1/2 -+
 *  | 14:32  DINSDAG    |  | 09:30  Title                      |
 *  | 3 NOVEMBER 2026   |  |        -10:30 location            |
 *  +- WEER ------------+  +- TAKEN ------------ 5 OPEN 1/3 ---+
 *  | icon 12° text     |  | [ ] task title          TE LAAT   |
 *  | forecast 3 days   |  | [ ] long task title that wraps    |
 *  +- VERJAARDAGEN ----+  |     onto a second line   VANDAAG  |
 *  | Anna     over 9 d |  | KLAAR VANDAAG                     |
 *  +-------------------+  | [x] done task                     |
 *  o bijgewerkt 14:30     +-----------------------------------+
 * </pre>
 * Nothing is dropped when lists are long: agenda and tasks are split into pages (see
 * {@link Paging}). A tap on a panel shows its next page; PAGE/VOLUME keys page the tasks
 * (forwarded by MainActivity). Without interaction, overflowing panels advance one page every
 * {@link #AUTO_PAGE_MINUTES} minutes together with the clock redraw, so no extra e-ink
 * refreshes are added.
 *
 * The panel is portrait-native. Instead of relying on system rotation (unverified on this e-ink
 * driver) the canvas is rotated here, so the activity stays portrait; touches are rotated back.
 */
public class DashboardView extends View {
    private static final float REF_WIDTH = 1024f;
    /** Four-tone palette: two greys stay clearly distinct on a 16-level e-ink panel. */
    private static final int INK = 0xFF000000;
    private static final int DARK = 0xFF555555;
    private static final int LIGHT = 0xFFAAAAAA;
    private static final int PAPER = Color.WHITE;
    /** Flash once per hour to clear e-ink ghosting. */
    private static final boolean HOURLY_FULL_REFRESH = true;
    private static final int AUTO_PAGE_MINUTES = 2;
    /** After a tap the chosen page stays put this long before auto-paging resumes. */
    private static final long HOLD_AFTER_TOUCH_MS = 3 * 60 * 1000L;
    /** Upper bounds for what is kept (bounded loops, bounded memory); far above a real day. */
    private static final int MAX_EVENTS = 200;
    private static final int MAX_TASKS = 500;
    private static final int MAX_BIRTHDAYS = 400;
    private static final int MAX_FORECAST_DAYS = 3;

    private static final String[] DAYS = {"zondag", "maandag", "dinsdag", "woensdag", "donderdag", "vrijdag", "zaterdag"};
    private static final String[] DAYS_SHORT = {"zo", "ma", "di", "wo", "do", "vr", "za"};
    private static final String[] MONTHS = {"januari", "februari", "maart", "april", "mei", "juni", "juli",
            "augustus", "september", "oktober", "november", "december"};
    private static final String[] MONTHS_SHORT = {"jan", "feb", "mrt", "apr", "mei", "jun", "jul", "aug",
            "sep", "okt", "nov", "dec"};

    /** One calendar event, parsed once from the JSON document. */
    private static final class Event {
        long start, end;
        boolean allDay;
        String title, location, calendar;
    }

    /** One task; due is local midnight, or 0 without due date. lines is filled by layout. */
    private static final class Task {
        long due;
        boolean done;
        int category;
        String title;
        List<String> lines;
    }

    private static final class Birthday {
        long date;
        int year, birthYear;
        String name;
    }

    private final Paint fill = new Paint();
    private final Paint fallback = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Calendar cal = Calendar.getInstance();

    private JSONObject data;
    private JSONObject weather;
    private final List<Event> events = new ArrayList<Event>();
    private final List<Task> tasks = new ArrayList<Task>();
    private final List<Birthday> birthdays = new ArrayList<Birthday>();
    private boolean tasksAvailable;
    /** null: no birthday data yet; FALSE: calendar "Verjaardagen" not found; TRUE: found. */
    private Boolean birthdayCalendar;
    private long generated;
    private String errorNote = "";
    private String status = "";
    private boolean inverted;
    private int lastMinute = -1;
    private int rotation = 90;
    private float s = 1f;

    // Paging state. Page counts are known after each draw.
    private int agendaPage, taskPage, agendaPages = 1, taskPages = 1;
    private long lastTouch;
    private final RectF agendaRect = new RectF(), tasksRect = new RectF();

    // Pixel-run sink state (no allocation per glyph).
    private Canvas sinkCanvas;
    private float sinkX, sinkTop;
    private int sinkScale;
    private final PixelFont.RunSink sink = new PixelFont.RunSink() {
        @Override
        public void run(int x, int y, int len) {
            sinkCanvas.drawRect(sinkX + x * sinkScale, sinkTop + y * sinkScale,
                    sinkX + (x + len) * sinkScale, sinkTop + (y + 1) * sinkScale, fill);
        }
    };

    public DashboardView(Context c) {
        super(c);
        fallback.setTypeface(Typeface.DEFAULT_BOLD);
        setClickable(true);
    }

    public void setData(JSONObject d, boolean fresh) {
        data = d;
        TimeZone tz = TimeZone.getDefault();
        weather = d == null ? null : d.optJSONObject("weather");
        generated = d == null ? 0 : TimeUtil.parseIso(d.optString("generated"), tz);
        parseEvents(d == null ? null : d.optJSONArray("events"), tz);
        JSONArray ts = d == null ? null : d.optJSONArray("tasks");
        tasksAvailable = ts != null;
        parseTasks(ts, tz);
        parseBirthdays(d == null ? null : d.optJSONObject("birthdays"), tz);
        JSONArray errs = d == null ? null : d.optJSONArray("errors");
        errorNote = "";
        if (errs != null && errs.length() > 0) {
            errorNote = errs.toString().contains("login verlopen") ? "login verlopen" : "deels oud";
        }
        status = fresh ? "" : "cache";
        invalidate();
    }

    public JSONObject getData() {
        return data;
    }

    public void setStatus(String st) {
        String v = st.toLowerCase();
        if (!v.equals(status)) {
            status = v;
            invalidate();
        }
    }

    /** 90 or 270: which way the reader lies on its side. */
    public void setLandscapeRotation(int degrees) {
        rotation = degrees == 270 ? 270 : 90;
        invalidate();
    }

    /** Pages the task list (hardware keys). */
    public void pageTasks(int direction) {
        lastTouch = System.currentTimeMillis();
        taskPage = (taskPage + direction + taskPages) % Math.max(taskPages, 1);
        invalidate();
    }

    /** Called every minute. Skips the draw when nothing visible changed. */
    public void redraw() {
        long now = System.currentTimeMillis();
        cal.setTimeInMillis(now);
        int minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        if (minute == lastMinute) return;
        boolean hourly = HOURLY_FULL_REFRESH && lastMinute >= 0 && cal.get(Calendar.MINUTE) == 0;
        lastMinute = minute;
        if (now - lastTouch >= HOLD_AFTER_TOUCH_MS && minute % AUTO_PAGE_MINUTES == 0) {
            agendaPage = agendaPages > 1 ? (agendaPage + 1) % agendaPages : 0;
            taskPage = taskPages > 1 ? (taskPage + 1) % taskPages : 0;
        }
        if (hourly) {
            inverted = true;
            invalidate();
            postDelayed(new Runnable() {
                @Override
                public void run() {
                    inverted = false;
                    invalidate();
                }
            }, 600);
        } else {
            invalidate();
        }
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() != MotionEvent.ACTION_UP) return true;
        // Undo the canvas rotation: portrait touch point -> landscape drawing coordinates.
        float px = ev.getX(), py = ev.getY();
        float lx, ly;
        if (getHeight() > getWidth()) {
            lx = rotation == 90 ? py : getHeight() - py;
            ly = rotation == 90 ? getWidth() - px : px;
        } else {
            lx = px;
            ly = py;
        }
        lastTouch = System.currentTimeMillis();
        if (agendaRect.contains(lx, ly) && agendaPages > 1) {
            agendaPage = (agendaPage + 1) % agendaPages;
        } else if (tasksRect.contains(lx, ly) && taskPages > 1) {
            taskPage = (taskPage + 1) % taskPages;
        } else {
            agendaPage = 0;  // tap elsewhere: back to the first pages
            taskPage = 0;
        }
        invalidate();
        performClick();
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    // ------------------------------------------------------------------ parsing

    private void parseEvents(JSONArray evs, TimeZone tz) {
        events.clear();
        int n = evs == null ? 0 : Math.min(evs.length(), MAX_EVENTS);
        for (int i = 0; i < n; i++) {
            JSONObject e = evs.optJSONObject(i);
            if (e == null) continue;
            Event ev = new Event();
            ev.start = TimeUtil.parseIso(e.optString("start"), tz);
            ev.end = TimeUtil.parseIso(e.optString("end"), tz);
            if (ev.start == 0 || ev.end == 0) continue;  // unparseable: skip rather than misplace
            ev.allDay = e.optBoolean("all_day");
            ev.title = e.optString("title");
            ev.location = e.optString("location");
            ev.calendar = e.optString("calendar");
            events.add(ev);
        }
    }

    private void parseTasks(JSONArray ts, TimeZone tz) {
        tasks.clear();
        int n = ts == null ? 0 : Math.min(ts.length(), MAX_TASKS);
        for (int i = 0; i < n; i++) {
            JSONObject t = ts.optJSONObject(i);
            if (t == null) continue;
            Task task = new Task();
            task.title = t.optString("title");
            task.due = TimeUtil.parseDate(t.optString("due"), tz);
            task.done = t.optBoolean("done", false);
            tasks.add(task);
        }
    }

    private void parseBirthdays(JSONObject b, TimeZone tz) {
        birthdays.clear();
        birthdayCalendar = b == null ? null : Boolean.valueOf(b.optBoolean("calendar"));
        JSONArray items = b == null ? null : b.optJSONArray("items");
        int n = items == null ? 0 : Math.min(items.length(), MAX_BIRTHDAYS);
        for (int i = 0; i < n; i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            Birthday bd = new Birthday();
            String date = o.optString("date");
            bd.date = TimeUtil.parseDate(date, tz);
            if (bd.date == 0) continue;
            bd.year = Integer.parseInt(date.substring(0, 4));  // parseDate validated the digits
            bd.name = o.optString("name");
            bd.birthYear = o.optInt("birth_year", 0);
            birthdays.add(bd);
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (h > w) {
            // Portrait panel: rotate so we draw in landscape coordinates.
            if (rotation == 90) {
                c.translate(w, 0);
                c.rotate(90);
            } else {
                c.translate(0, h);
                c.rotate(-90);
            }
            int t = w;
            w = h;
            h = t;
        }
        s = w / REF_WIDTH;
        if (inverted) {
            c.drawColor(INK);
            return;
        }
        c.drawColor(PAPER);
        long now = System.currentTimeMillis();
        TimeZone tz = TimeZone.getDefault();
        cal.setTimeZone(tz);  // follow a changed device time zone
        long today = TimeUtil.startOfDay(now, tz);
        long tomorrow = TimeUtil.addDays(now, 1, tz);

        float m = d(12), gap = d(12);
        float leftR = m + d(340);
        float rightL = leftR + gap;
        float right = w - m, bottom = h - m;

        float y = drawClockPanel(c, now, m, m, leftR);
        y = drawWeatherPanel(c, m, y + gap, leftR, tz);
        drawStatus(c, m, bottom);
        drawBirthdayPanel(c, today, m, y + gap, leftR, bottom - d(30));

        List<Event> todays = todaysEvents(today, tomorrow);
        List<Object> taskRows = taskRows(today, right - rightL);
        float avail = bottom - m - gap;
        float needA = panelChrome() + Math.max(sum(agendaHeights(todays)), d(44));
        float needT = panelChrome() + Math.max(sum(taskHeights(taskRows)), d(60));
        float agendaH = needA + needT <= avail ? needA
                : Math.max(panelChrome() + d(60), Math.min(needA, Math.max(avail * 0.45f, avail - needT)));
        agendaRect.set(rightL, m, right, m + agendaH);
        tasksRect.set(rightL, m + agendaH + gap, right, bottom);
        drawAgendaPanel(c, now, today, todays, agendaRect);
        drawTaskPanel(c, today, tomorrow, taskRows, tasksRect);
    }

    /** Device pixels for a reference-layout length. */
    private float d(float ref) {
        return ref * s;
    }

    /** Integer pixel-font scale for a reference scale, so font pixels stay crisp squares. */
    private int px(int ref) {
        return Math.max(1, Math.round(ref * s));
    }

    private float panelChrome() {
        return d(36) + d(14);
    }

    // ------------------------------------------------------------------ left column

    private float drawClockPanel(Canvas c, long now, float l, float t, float r) {
        float b = t + d(214);
        panel(c, l, t, r, b, null, null, null);
        cal.setTimeInMillis(now);
        String time = hm(now);
        // Largest integer scale at which "88:88" fits the panel width.
        int scale = Math.max(1, (int) ((r - l - d(44)) / PixelFont.measure("88:88")));
        float tw = PixelFont.measure(time) * scale;
        float x = l + (r - l - tw) / 2;
        float base = t + d(24) + PixelFont.CAP * scale;
        int shadow = Math.max(1, scale / 3);
        text(c, time, x + shadow, base + shadow, scale, LIGHT);  // 8-bit title-screen drop shadow
        text(c, time, x, base, scale, INK);

        String day = DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1].toUpperCase();
        String date = cal.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[cal.get(Calendar.MONTH)].toUpperCase()
                + " " + cal.get(Calendar.YEAR);
        base += d(22) + PixelFont.CAP * px(4);
        centred(c, day, l, r, base, px(4), INK);
        base += d(14) + PixelFont.CAP * px(3);
        centred(c, date, l, r, base, px(3), DARK);
        return b;
    }

    private float drawWeatherPanel(Canvas c, float l, float t, float r, TimeZone tz) {
        float b = t + d(266);
        JSONObject wx = weather;
        String loc = wx == null ? "Den Bosch" : wx.optString("location", "Den Bosch");
        panel(c, l, t, r, b, "WEER " + loc.toUpperCase(), null, null);
        float cl = l + d(20), top = t + d(38);
        if (wx == null) {
            text(c, "Nog geen weer", cl, top + d(24), px(3), DARK);
            return b;
        }
        int code = wx.optInt("code");
        sprite(c, Sprites.weather(code, wx.optBoolean("is_day", true)), cl, top, px(5));
        String temp = Math.round(wx.optDouble("temp")) + "°";
        int ts = px(8);
        float tx = cl + d(80) + d(18);
        text(c, temp, tx, top + d(4) + PixelFont.CAP * ts, ts, INK);
        float y = top + d(80) + d(30);
        text(c, PixelFont.ellipsize(wx.optString("text"), (int) ((r - cl - d(16)) / px(3))), cl, y, px(3), INK);

        JSONArray days = wx.optJSONArray("days");
        JSONObject d0 = days == null ? null : days.optJSONObject(0);
        if (d0 != null) {
            int rp = d0.optInt("rain_pct", -1);
            String line = "max " + Math.round(d0.optDouble("max")) + "°  min " + Math.round(d0.optDouble("min")) + "°"
                    + (rp >= 0 ? "  regen " + rp + "%" : "");
            y += d(26);
            text(c, line.toUpperCase(), cl, y, px(2), DARK);
        }
        // Next days: three columns of day, icon, max and min.
        float colW = (r - cl - d(16)) / MAX_FORECAST_DAYS;
        float rowTop = y + d(16);
        int n = days == null ? 0 : Math.min(days.length(), MAX_FORECAST_DAYS + 1);
        for (int i = 1; i < n; i++) {
            JSONObject dd = days.optJSONObject(i);
            if (dd == null) continue;
            float cx = cl + (i - 1) * colW;
            long date = TimeUtil.parseDate(dd.optString("date"), tz);
            text(c, date == 0 ? "" : dayShort(date).toUpperCase(), cx, rowTop + d(14), px(2), INK);
            sprite(c, Sprites.weather(dd.optInt("code"), true), cx + d(34), rowTop - d(2), px(2));
            String mx = Math.round(dd.optDouble("max")) + "°";
            String mn = Math.round(dd.optDouble("min")) + "°";
            text(c, mx, cx, rowTop + d(54), px(2), INK);
            text(c, mn, cx + d(42), rowTop + d(54), px(2), DARK);
        }
        return b;
    }

    /** Small birthday widget: today's birthdays highlighted, then the next ones. */
    private void drawBirthdayPanel(Canvas c, long today, float l, float t, float r, float b) {
        boolean anyToday = false;
        for (int i = 0; i < birthdays.size(); i++) {
            if (birthdays.get(i).date == today) anyToday = true;
        }
        panel(c, l, t, r, b, anyToday ? "JARIG VANDAAG!" : "VERJAARDAGEN", Sprites.CAKE, null);
        float cl = l + d(20), cr = r - d(20);
        float y = t + d(36);
        if (birthdayCalendar == null || !birthdayCalendar.booleanValue()) {
            String[] msg = birthdayCalendar == null ? new String[]{"NOG NIET GELADEN"}
                    : new String[]{"MAAK IN GOOGLE AGENDA", "DE AGENDA \"VERJAARDAGEN\"", "MET JAARLIJKSE", "HELE-DAG-AFSPRAKEN"};
            for (int i = 0; i < msg.length; i++) text(c, msg[i], cl, y + d(14) + i * d(22), px(2), DARK);
            return;
        }
        float rowH = d(48);
        int shown = 0;
        for (int i = 0; i < birthdays.size() && y + rowH <= b - d(8); i++) {
            Birthday bd = birthdays.get(i);
            int days = Birthdays.daysUntil(today, bd.date);
            if (days < 0) continue;  // stale cache after midnight
            drawBirthdayRow(c, bd, days, cl, cr, y, rowH);
            y += rowH;
            shown++;
        }
        if (shown == 0) text(c, "GEEN VERJAARDAGEN", cl, y + d(16), px(2), DARK);
    }

    private void drawBirthdayRow(Canvas c, Birthday bd, int days, float l, float r, float y, float rowH) {
        boolean isToday = days == 0;
        int fg = isToday ? PAPER : INK;
        if (isToday) {
            fill.setColor(INK);
            c.drawRect(l - d(8), y, r + d(8), y + rowH - d(4), fill);
        }
        String when = isToday ? "VANDAAG!" : days == 1 ? "MORGEN" : "OVER " + days + " D";
        float ww = PixelFont.measure(when) * px(2);
        text(c, when, r - ww, y + d(20), px(2), fg);
        float base = y + d(4) + PixelFont.CAP * px(3);
        text(c, PixelFont.ellipsize(bd.name, (int) ((r - l - ww - d(12)) / px(3))), l, base, px(3), fg);
        int age = Birthdays.age(bd.birthYear, bd.year);
        String sub = dueLabel(bd.date).toUpperCase() + (age > 0 ? " - WORDT " + age : "");
        text(c, sub, l, base + d(18), px(2), isToday ? PAPER : DARK);
    }

    private void drawStatus(Canvas c, float l, float base) {
        boolean ok = status.isEmpty() && errorNote.isEmpty() && generated > 0;
        sprite(c, ok ? Sprites.LED_ON : Sprites.LED_OFF, l + d(4), base - d(12), px(2));
        String problem = !status.isEmpty() ? status : errorNote;
        String upd = generated > 0 ? (problem.isEmpty() ? "bijgewerkt " + hm(generated) : hm(generated) + " - " + problem)
                : (problem.isEmpty() ? "wacht op gegevens" : problem);
        text(c, upd.toUpperCase(), l + d(22), base - d(2), px(2), DARK);
    }

    // ------------------------------------------------------------------ agenda

    /** Today's events, including multi-day events still running. */
    private List<Event> todaysEvents(long today, long tomorrow) {
        List<Event> out = new ArrayList<Event>();
        for (int i = 0; i < events.size(); i++) {
            Event e = events.get(i);
            if (e.start < tomorrow && e.end > today) out.add(e);
        }
        return out;
    }

    private boolean hasSecondLine(Event e) {
        return !e.allDay || !e.location.isEmpty() || !e.calendar.isEmpty();
    }

    private float[] agendaHeights(List<Event> evs) {
        float[] h = new float[evs.size()];
        for (int i = 0; i < h.length; i++) h[i] = hasSecondLine(evs.get(i)) ? d(56) : d(38);
        return h;
    }

    private void drawAgendaPanel(Canvas c, long now, long today, List<Event> evs, RectF box) {
        float[] heights = agendaHeights(evs);
        float cl = box.left + d(20), cr = box.right - d(20), top = box.top + d(36);
        int[] pages = Paging.pageStarts(heights, box.bottom - d(14) - top, null);
        agendaPages = pages.length;
        if (agendaPage >= agendaPages) agendaPage = 0;
        String right = agendaPages > 1 ? (agendaPage + 1) + "/" + agendaPages + " TIK" : null;
        panel(c, box.left, box.top, box.right, box.bottom, "VANDAAG", null, right);
        if (evs.isEmpty()) {
            text(c, "Geen afspraken vandaag.", cl, top + d(24), px(3), DARK);
            return;
        }
        float y = top;
        int end = Paging.pageEnd(pages, agendaPage, evs.size());
        for (int i = pages[agendaPage]; i < end; i++) {
            drawEvent(c, evs.get(i), now, today, cl, cr, y, heights[i]);
            y += heights[i];
        }
    }

    private void drawEvent(Canvas c, Event e, long now, long today, float l, float r, float y, float rowH) {
        boolean current = !e.allDay && e.start <= now && now < e.end;
        boolean past = !e.allDay && e.end <= now;
        int fg = current ? PAPER : past ? DARK : INK;
        if (current) {  // the running event is shown as the selected menu entry
            fill.setColor(INK);
            c.drawRect(l - d(10), y, r + d(10), y + rowH - d(4), fill);
        }
        float base = y + d(6) + PixelFont.CAP * px(3);
        float titleX = l + d(96);
        if (e.allDay) {
            text(c, "HELE DAG", l, base, px(2), fg);
        } else if (e.start < today) {
            text(c, "EERDER", l, base, px(2), fg);
        } else {
            text(c, hm(e.start), l, base, px(3), fg);
        }
        text(c, PixelFont.ellipsize(e.title, (int) ((r - titleX) / px(3))), titleX, base, px(3), fg);
        if (!hasSecondLine(e)) return;
        float base2 = base + d(20);
        int fg2 = current ? PAPER : DARK;
        if (!e.allDay) text(c, "-" + hm(e.end), l, base2, px(2), fg2);
        String meta = e.location;
        if (!e.calendar.isEmpty()) meta = meta.isEmpty() ? e.calendar : meta + " - " + e.calendar;
        text(c, PixelFont.ellipsize(meta, (int) ((r - titleX) / px(2))), titleX, base2, px(2), fg2);
    }

    // ------------------------------------------------------------------ tasks

    /** Ordered rows: open tasks (TaskOrder), then a "KLAAR VANDAAG" header and completed tasks. */
    private List<Object> taskRows(final long today, float panelWidth) {
        List<Task> sorted = new ArrayList<Task>(tasks);
        for (int i = 0; i < sorted.size(); i++) {
            Task t = sorted.get(i);
            t.category = TaskOrder.category(t.due, today, t.done);
        }
        Collections.sort(sorted, new Comparator<Task>() {
            @Override
            public int compare(Task a, Task b) {
                return TaskOrder.compare(a.category, a.due, a.title, b.category, b.due, b.title);
            }
        });
        List<Object> rows = new ArrayList<Object>();
        float textW = panelWidth - d(40) - d(34);
        boolean header = false;
        for (int i = 0; i < sorted.size(); i++) {
            Task t = sorted.get(i);
            if (t.done && !header) {  // completed tasks sort last: one header before the first
                rows.add("KLAAR VANDAAG");
                header = true;
            }
            float badge = t.done || t.due == 0 ? 0 : PixelFont.measure(dueText(t, today)) * px(2) + d(28);
            t.lines = PixelFont.wrap(t.title, (int) ((textW - badge) / px(3)), 2);
            rows.add(t);
        }
        return rows;
    }

    private float[] taskHeights(List<Object> rows) {
        float[] h = new float[rows.size()];
        for (int i = 0; i < h.length; i++) {
            Object o = rows.get(i);
            h[i] = o instanceof String ? d(30) : d(38) + (Math.max(((Task) o).lines.size(), 1) - 1) * d(28);
        }
        return h;
    }

    private void drawTaskPanel(Canvas c, long today, long tomorrow, List<Object> rows, RectF box) {
        float[] heights = taskHeights(rows);
        float cl = box.left + d(20), cr = box.right - d(20), top = box.top + d(36);
        int open = 0;
        for (int i = 0; i < tasks.size(); i++) if (!tasks.get(i).done) open++;
        float listTop = open == 0 ? top + d(40) : top;  // room for "ALLES GEDAAN!"
        boolean[] header = new boolean[rows.size()];
        for (int i = 0; i < header.length; i++) header[i] = rows.get(i) instanceof String;
        int[] pages = Paging.pageStarts(heights, box.bottom - d(14) - listTop, header);
        taskPages = pages.length;
        if (taskPage >= taskPages) taskPage = 0;
        String right = !tasksAvailable ? null
                : open + " OPEN" + (taskPages > 1 ? "  " + (taskPage + 1) + "/" + taskPages + " TIK" : "");
        panel(c, box.left, box.top, box.right, box.bottom, "TAKEN", null, right);
        if (!tasksAvailable) {
            text(c, "Taken nog niet geladen.", cl, top + d(24), px(3), DARK);
            return;
        }
        if (open == 0) {
            sprite(c, Sprites.STAR, cl, top + d(2), px(3));
            text(c, "ALLES GEDAAN!", cl + d(40), top + d(24), px(3), INK);
            if (rows.isEmpty()) return;
        }
        float y = listTop;
        int end = Paging.pageEnd(pages, taskPage, rows.size());
        for (int i = pages[taskPage]; i < end; i++) {
            Object o = rows.get(i);
            if (o instanceof String) {
                text(c, (String) o, cl, y + d(22), px(2), DARK);
                fill.setColor(LIGHT);
                float lw = PixelFont.measure((String) o) * px(2) + d(10);
                c.drawRect(cl + lw, y + d(16), cr, y + d(18), fill);
            } else {
                drawTask(c, (Task) o, today, tomorrow, cl, cr, y);
            }
            y += heights[i];
        }
    }

    private void drawTask(Canvas c, Task t, long today, long tomorrow, float l, float r, float y) {
        float base = y + d(6) + PixelFont.CAP * px(3);
        sprite(c, t.done ? Sprites.BOX_CHECKED : Sprites.BOX, l, base - 7 * px(3), px(3));
        int fg = t.done ? DARK : INK;
        float tx = l + d(34);
        for (int k = 0; k < t.lines.size(); k++) {
            float b = base + k * d(28);
            float w = text(c, t.lines.get(k), tx, b, px(3), fg);
            if (t.done) {  // strike-through for completed tasks
                fill.setColor(DARK);
                c.drawRect(tx, b - d(11), tx + w, b - d(9), fill);
            }
        }
        if (t.done || t.due == 0) return;
        String due = dueText(t, today);
        float bw = PixelFont.measure(due) * px(2);
        float bx = r - bw - d(8), by = base - d(17);
        if (t.category == TaskOrder.OVERDUE) {  // inverted badge: most urgent
            fill.setColor(INK);
            c.drawRect(bx - d(6), by, r, by + d(22), fill);
            text(c, due, bx, by + d(18), px(2), PAPER);
        } else if (t.category == TaskOrder.TODAY) {  // outlined badge
            frame(c, bx - d(6), by, r, by + d(22), d(2), INK);
            text(c, due, bx, by + d(18), px(2), INK);
        } else {
            text(c, due, bx, by + d(18), px(2), DARK);
        }
    }

    private String dueText(Task t, long today) {
        if (t.due < today) return "TE LAAT";
        if (t.due == today) return "VANDAAG";
        if (t.due == TimeUtil.addDays(today, 1, TimeZone.getDefault())) return "MORGEN";
        return dueLabel(t.due).toUpperCase();
    }

    // ------------------------------------------------------------------ pixel primitives

    /**
     * Retro window: notched double border; an inverted title tab at the top left (with an
     * optional sprite) and an optional inverted tab at the top right (page indicator).
     */
    private void panel(Canvas c, float l, float t, float r, float b, String title, String[] icon, String right) {
        float o = d(4);
        fill.setColor(INK);
        c.drawRect(l + o, t, r - o, t + o, fill);        // outer border, corners left open
        c.drawRect(l + o, b - o, r - o, b, fill);
        c.drawRect(l, t + o, l + o, b - o, fill);
        c.drawRect(r - o, t + o, r, b - o, fill);
        frame(c, l + d(7), t + d(7), r - d(7), b - d(7), d(2), INK);  // inner line
        if (title != null) {
            float iconW = icon == null ? 0 : icon[0].length() * px(2) + d(8);
            float tw = PixelFont.measure(title) * px(2);
            fill.setColor(INK);
            c.drawRect(l + d(14), t, l + d(14) + iconW + tw + d(20), t + d(28), fill);
            if (icon != null) spriteInverted(c, icon, l + d(24), t + d(3), px(2));
            text(c, title, l + d(24) + iconW, t + d(21), px(2), PAPER);
        }
        if (right != null) {
            float rw = PixelFont.measure(right) * px(2);
            fill.setColor(INK);
            c.drawRect(r - d(14) - rw - d(20), t, r - d(14), t + d(28), fill);
            text(c, right, r - d(24) - rw, t + d(21), px(2), PAPER);
        }
    }

    private void frame(Canvas c, float l, float t, float r, float b, float th, int color) {
        fill.setColor(color);
        c.drawRect(l, t, r, t + th, fill);
        c.drawRect(l, b - th, r, b, fill);
        c.drawRect(l, t, l + th, b, fill);
        c.drawRect(r - th, t, r, b, fill);
    }

    /** Pixel text with its baseline at base. Returns the width drawn, in device pixels. */
    private float text(Canvas c, String t, float x, float base, int scale, int color) {
        List<Object> units = PixelFont.units(t);
        fill.setColor(color);
        sinkCanvas = c;
        sinkScale = scale;
        sinkTop = base - PixelFont.CAP * scale;
        float cx = x;
        for (int i = 0; i < units.size(); i++) {
            PixelFont.Unit u = (PixelFont.Unit) units.get(i);
            if (u.glyph == null) {
                drawFallback(c, u.fallback, cx, base, scale, color);
                fill.setColor(color);
            } else {
                sinkX = cx;
                PixelFont.emit(u, 0, sink);
            }
            cx += (u.width() + PixelFont.SPACING) * scale;
        }
        return units.isEmpty() ? 0 : cx - x - PixelFont.SPACING * scale;
    }

    /** A character the pixel font lacks (e.g. emoji, rare scripts): system font in a 5-px cell. */
    private void drawFallback(Canvas c, String t, float x, float base, int scale, int color) {
        fallback.setColor(color);
        fallback.setTextSize(PixelFont.CAP * scale * 1.3f);
        fallback.setTextScaleX(1f);
        float w = fallback.measureText(t), cell = 5 * scale;
        if (w > cell) fallback.setTextScaleX(cell / w);
        c.drawText(t, x, base, fallback);
    }

    private void centred(Canvas c, String t, float l, float r, float base, int scale, int color) {
        float w = PixelFont.measure(t) * scale;
        text(c, t, l + (r - l - w) / 2, base, scale, color);
    }

    private void sprite(Canvas c, String[] rows, float x, float y, int scale) {
        drawSprite(c, rows, x, y, scale, false);
    }

    /** Sprite on a black tab: ink becomes paper, greys stay grey. */
    private void spriteInverted(Canvas c, String[] rows, float x, float y, int scale) {
        drawSprite(c, rows, x, y, scale, true);
    }

    private void drawSprite(Canvas c, String[] rows, float x, float y, int scale, boolean inv) {
        for (int r = 0; r < rows.length; r++) {
            String row = rows[r];
            int i = 0;
            while (i < row.length()) {  // bounded by the row width
                char ch = row.charAt(i);
                int j = i;
                while (j < row.length() && row.charAt(j) == ch) j++;
                if (ch != '.') {
                    fill.setColor(ch == '#' ? (inv ? PAPER : INK) : ch == '+' ? DARK : LIGHT);
                    c.drawRect(x + i * scale, y + r * scale, x + j * scale, y + (r + 1) * scale, fill);
                }
                i = j;
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static float sum(float[] a) {
        float t = 0;
        for (int i = 0; i < a.length; i++) t += a[i];
        return t;
    }

    private static String two(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    private String hm(long ms) {
        cal.setTimeInMillis(ms);
        return two(cal.get(Calendar.HOUR_OF_DAY)) + ":" + two(cal.get(Calendar.MINUTE));
    }

    private String dayShort(long ms) {
        cal.setTimeInMillis(ms);
        return DAYS_SHORT[cal.get(Calendar.DAY_OF_WEEK) - 1];
    }

    private String dueLabel(long ms) {
        cal.setTimeInMillis(ms);
        return DAYS_SHORT[cal.get(Calendar.DAY_OF_WEEK) - 1] + " " + cal.get(Calendar.DAY_OF_MONTH)
                + " " + MONTHS_SHORT[cal.get(Calendar.MONTH)];
    }
}
