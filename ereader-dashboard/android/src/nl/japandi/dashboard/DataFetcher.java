package nl.japandi.dashboard;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Fetches Google Calendar, Google Tasks (both read-only) and Open-Meteo weather directly on the
 * reader and builds the JSON document the view draws. Every source is fetched independently; a
 * failing source keeps its previous value and is listed in "errors".
 *
 * Agenda: today and tomorrow are fetched (tomorrow only so the screen is right straight after
 * midnight, before the next sync); the view shows today only. Birthdays come from the calendar
 * named "Verjaardagen" (see {@link Birthdays}); that calendar is left out of the agenda.
 */
class DataFetcher {
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String CAL = "https://www.googleapis.com/calendar/v3";
    private static final String TASKS = "https://tasks.googleapis.com/tasks/v1";
    /** Days after today to fetch for the agenda (see class comment). */
    private static final int DAYS_AHEAD = 1;
    /** Birthdays are looked up this many days ahead: one full year covers every yearly event. */
    private static final int BIRTHDAY_DAYS = 366;
    private static final double LAT = 51.6978, LON = 5.3037;  // 's-Hertogenbosch (Den Bosch)
    private static final String LOCATION = "Den Bosch";
    /** Upper bounds: every loop and every response read has a fixed limit. */
    private static final int MAX_PAGES = 10;
    private static final int MAX_CALENDARS = 50;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    /** Thrown when Google refuses the refresh token (revoked, or expired after 7 days in testing). */
    static class AuthExpired extends IOException {
        private static final long serialVersionUID = 1L;

        AuthExpired(String m) {
            super(m);
        }
    }

    private final String clientId, clientSecret, refreshToken;
    private final SSLSocketFactory tls;
    private String accessToken;
    private long accessExpiry;
    private boolean unreachable;
    /** calendarList of the current build() call; fetched once, shared by agenda and birthdays. */
    private JSONArray calendars;

    DataFetcher(Context ctx, String clientId, String clientSecret, String refreshToken) throws Exception {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.refreshToken = refreshToken;
        this.tls = trustWithIsrgRoots(ctx);
    }

    /** True when the last {@link #build} reached none of the sources (no network). */
    boolean wasUnreachable() {
        return unreachable;
    }

    JSONObject build(JSONObject previous) throws JSONException {
        JSONObject out = new JSONObject();
        JSONArray errors = new JSONArray();
        JSONObject updated = previous != null && previous.optJSONObject("updated") != null
                ? new JSONObject(previous.optJSONObject("updated").toString()) : new JSONObject();
        String now = TimeUtil.formatLocal(System.currentTimeMillis(), TimeZone.getDefault());
        out.put("generated", now);

        // Count only network-level failures: an expired login is not "offline".
        int networkFailures = 0;
        Object weather = null, events = null, tasks = null, birthdays = null;
        calendars = null;
        try {
            weather = fetchWeather();
            updated.put("weather", now);
        } catch (IOException e) {
            networkFailures++;
            errors.put("weather: " + e.getClass().getSimpleName());
        } catch (JSONException e) {
            errors.put("weather: " + e.getClass().getSimpleName());
        }
        try {
            events = fetchEvents();
            updated.put("events", now);
        } catch (AuthExpired e) {
            errors.put("google: login verlopen");
        } catch (IOException e) {
            networkFailures++;
            errors.put("events: " + e.getClass().getSimpleName());
        } catch (JSONException e) {
            errors.put("events: " + e.getClass().getSimpleName());
        }
        try {
            tasks = fetchTasks();
            updated.put("tasks", now);
        } catch (AuthExpired e) {
            errors.put("google: login verlopen");
        } catch (IOException e) {
            networkFailures++;
            errors.put("tasks: " + e.getClass().getSimpleName());
        } catch (JSONException e) {
            errors.put("tasks: " + e.getClass().getSimpleName());
        }
        try {
            birthdays = fetchBirthdays();
            updated.put("birthdays", now);
        } catch (AuthExpired e) {
            errors.put("google: login verlopen");
        } catch (IOException e) {
            errors.put("birthdays: " + e.getClass().getSimpleName());
        } catch (JSONException e) {
            errors.put("birthdays: " + e.getClass().getSimpleName());
        }
        unreachable = networkFailures == 3;  // weather, agenda and tasks all failed on the network
        out.put("weather", weather != null ? weather : prev(previous, "weather"));
        out.put("events", events != null ? events : prev(previous, "events"));
        out.put("tasks", tasks != null ? tasks : prev(previous, "tasks"));
        out.put("birthdays", birthdays != null ? birthdays : prev(previous, "birthdays"));
        out.put("errors", errors);
        out.put("updated", updated);
        return out;
    }

    private static Object prev(JSONObject p, String k) {
        return p == null || p.isNull(k) ? JSONObject.NULL : p.opt(k);
    }

    // ------------------------------------------------------------------ Google

    private String token() throws IOException, JSONException {
        if (accessToken != null && System.currentTimeMillis() < accessExpiry - 60000) return accessToken;
        String body = "client_id=" + enc(clientId) + "&client_secret=" + enc(clientSecret)
                + "&refresh_token=" + enc(refreshToken) + "&grant_type=refresh_token";
        HttpsURLConnection c = open(TOKEN_URL);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        OutputStream os = c.getOutputStream();
        os.write(body.getBytes("UTF-8"));
        os.close();
        int code = c.getResponseCode();
        if (code == 400 || code == 401) {
            c.disconnect();
            throw new AuthExpired("token refresh refused: " + code);
        }
        JSONObject j = new JSONObject(read(c));
        accessToken = j.getString("access_token");
        accessExpiry = System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000L;
        return accessToken;
    }

    private JSONObject getJson(String url) throws IOException, JSONException {
        HttpsURLConnection c = open(url);
        c.setRequestProperty("Authorization", "Bearer " + token());
        int code = c.getResponseCode();
        if (code == 401) {
            accessToken = null;  // token rejected: refresh once on the next call
            c.disconnect();
            throw new IOException("401 from Google");
        }
        if (code != 200) {
            c.disconnect();
            throw new IOException("HTTP " + code);
        }
        return new JSONObject(read(c));
    }

    private JSONArray fetchEvents() throws IOException, JSONException {
        final TimeZone tz = TimeZone.getDefault();
        long now = System.currentTimeMillis();
        String timeMin = enc(TimeUtil.formatUtc(TimeUtil.startOfDay(now, tz)));
        String timeMax = enc(TimeUtil.formatUtc(TimeUtil.addDays(now, DAYS_AHEAD + 1, tz)));

        JSONArray cals = calendarList();
        List<JSONObject> out = new ArrayList<JSONObject>();
        Set<String> seen = new HashSet<String>();
        int nCals = cals == null ? 0 : Math.min(cals.length(), MAX_CALENDARS);
        for (int i = 0; i < nCals; i++) {
            JSONObject cal = cals.getJSONObject(i);
            if (!cal.optBoolean("selected")) continue;  // same calendars as visible in Google Calendar
            if (isBirthdayCalendar(cal)) continue;      // shown in the birthday widget instead
            // The main calendar gets no label; other calendars (e.g. a TimeTree import) show their name.
            String label = cal.optBoolean("primary") ? ""
                    : cal.optString("summaryOverride", cal.optString("summary", ""));
            String page = null;
            int pages = 0;
            do {
                String url = CAL + "/calendars/" + enc(cal.getString("id")) + "/events?singleEvents=true"
                        + "&orderBy=startTime&showDeleted=false&maxResults=250"
                        + "&timeMin=" + timeMin + "&timeMax=" + timeMax
                        + (page != null ? "&pageToken=" + enc(page) : "");
                JSONObject resp = getJson(url);
                JSONArray items = resp.optJSONArray("items");
                for (int k = 0; items != null && k < items.length(); k++) {
                    JSONObject e = items.getJSONObject(k);
                    if ("cancelled".equals(e.optString("status"))) continue;
                    JSONObject s = e.getJSONObject("start"), en = e.getJSONObject("end");
                    boolean allDay = !s.has("dateTime");
                    String st = allDay ? dateToIso(s.getString("date"), tz) : s.getString("dateTime");
                    String et = allDay ? dateToIso(en.getString("date"), tz) : en.getString("dateTime");
                    // The same event can appear in several calendars (shared or subscribed).
                    String key = e.optString("iCalUID", e.optString("id")) + "|" + st;
                    if (!seen.add(key)) continue;
                    JSONObject o = new JSONObject();
                    o.put("title", e.optString("summary", "(geen titel)"));
                    o.put("start", st);
                    o.put("end", et);
                    o.put("all_day", allDay);
                    o.put("location", e.optString("location", ""));
                    o.put("calendar", label);
                    out.add(o);
                }
                page = resp.optString("nextPageToken", null);
            } while (page != null && !page.isEmpty() && ++pages < MAX_PAGES);
        }
        Collections.sort(out, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                long d = TimeUtil.parseIso(a.optString("start"), tz) - TimeUtil.parseIso(b.optString("start"), tz);
                if (d != 0) return d < 0 ? -1 : 1;
                return Boolean.compare(!a.optBoolean("all_day"), !b.optBoolean("all_day"));
            }
        });
        return new JSONArray(out);
    }

    /**
     * All open tasks of every list, plus the tasks completed today (so finished work stays
     * visible). Read-only: nothing is ever written back. Ordering is done by the view.
     */
    private JSONArray fetchTasks() throws IOException, JSONException {
        JSONArray lists = getJson(TASKS + "/users/@me/lists?maxResults=100").optJSONArray("items");
        List<JSONObject> out = new ArrayList<JSONObject>();
        Set<String> seen = new HashSet<String>();
        String doneSince = enc(TimeUtil.formatUtc(TimeUtil.startOfDay(System.currentTimeMillis(), TimeZone.getDefault())));
        int nLists = lists == null ? 0 : Math.min(lists.length(), 100);
        for (int i = 0; i < nLists; i++) {
            JSONObject tl = lists.getJSONObject(i);
            String base = TASKS + "/lists/" + enc(tl.getString("id")) + "/tasks?maxResults=100";
            addTasks(base + "&showCompleted=false&showHidden=false", tl, false, seen, out);
            // showHidden is needed for tasks completed in Google's own apps (they are hidden there).
            addTasks(base + "&showCompleted=true&showHidden=true&completedMin=" + doneSince, tl, true, seen, out);
        }
        return new JSONArray(out);
    }

    /** One paged tasks.list query. completedOnly keeps only status "completed". */
    private void addTasks(String query, JSONObject list, boolean completedOnly, Set<String> seen,
                          List<JSONObject> out) throws IOException, JSONException {
        String page = null;
        int pages = 0;
        do {
            JSONObject resp = getJson(query + (page != null ? "&pageToken=" + enc(page) : ""));
            JSONArray items = resp.optJSONArray("items");
            for (int k = 0; items != null && k < items.length(); k++) {
                JSONObject t = items.getJSONObject(k);
                boolean done = "completed".equals(t.optString("status"));
                if (done != completedOnly) continue;
                if (t.optString("title").trim().isEmpty()) continue;
                String id = t.optString("id");
                if (!id.isEmpty() && !seen.add(id)) continue;  // never list a task twice
                JSONObject o = new JSONObject();
                o.put("title", t.getString("title"));
                String due = t.optString("due", "");
                // Google Tasks stores only the date part of 'due' (time is always 00:00 UTC).
                o.put("due", due.length() >= 10 ? due.substring(0, 10) : "");
                o.put("list", list.optString("title"));
                o.put("done", done);
                out.add(o);
            }
            page = resp.optString("nextPageToken", null);
        } while (page != null && !page.isEmpty() && ++pages < MAX_PAGES);
    }

    // ------------------------------------------------------------------ birthdays

    private JSONArray calendarList() throws IOException, JSONException {
        if (calendars == null) {
            JSONArray items = getJson(CAL + "/users/me/calendarList?maxResults=" + MAX_CALENDARS).optJSONArray("items");
            calendars = items != null ? items : new JSONArray();
        }
        return calendars;
    }

    private static boolean isBirthdayCalendar(JSONObject cal) {
        return Birthdays.CALENDAR_NAME.equalsIgnoreCase(cal.optString("summaryOverride", "").trim())
                || Birthdays.CALENDAR_NAME.equalsIgnoreCase(cal.optString("summary", "").trim());
    }

    /**
     * Upcoming birthdays from the "Verjaardagen" calendar, whether or not it is shown in Google
     * Calendar. Result: {"calendar": found?, "items": [{name, date, birth_year}]}, sorted by
     * date. The calendar is only read; it is never created or changed from here.
     */
    private JSONObject fetchBirthdays() throws IOException, JSONException {
        JSONObject result = new JSONObject();
        JSONArray cals = calendarList();
        String calId = null;
        for (int i = 0; i < Math.min(cals.length(), MAX_CALENDARS) && calId == null; i++) {
            JSONObject c = cals.getJSONObject(i);
            if (isBirthdayCalendar(c)) calId = c.getString("id");
        }
        result.put("calendar", calId != null);
        JSONArray items = new JSONArray();
        result.put("items", items);
        if (calId == null) return result;

        String base = CAL + "/calendars/" + enc(calId) + "/events?showDeleted=false&maxResults=250";
        Set<String> yearly = yearlySeries(base);

        // Actual occurrences in the coming year (Google expands the recurrence, incl. moved ones).
        TimeZone tz = TimeZone.getDefault();
        long now = System.currentTimeMillis();
        String range = "&singleEvents=true&orderBy=startTime"
                + "&timeMin=" + enc(TimeUtil.formatUtc(TimeUtil.startOfDay(now, tz)))
                + "&timeMax=" + enc(TimeUtil.formatUtc(TimeUtil.addDays(now, BIRTHDAY_DAYS, tz)));
        int maxYear = java.util.Calendar.getInstance(tz).get(java.util.Calendar.YEAR);
        String page = null;
        int pages = 0;
        do {
            JSONObject resp = getJson(base + range + (page != null ? "&pageToken=" + enc(page) : ""));
            JSONArray evs = resp.optJSONArray("items");
            for (int k = 0; evs != null && k < evs.length(); k++) {
                JSONObject e = evs.getJSONObject(k);
                if ("cancelled".equals(e.optString("status"))) continue;
                JSONObject st = e.optJSONObject("start");
                boolean allDay = st != null && st.has("date");
                boolean isYearly = yearly.contains(e.optString("recurringEventId"));
                if (!Birthdays.isBirthday(allDay, isYearly, e.optString("eventType"))) continue;
                JSONObject o = new JSONObject();
                o.put("name", Birthdays.name(e.optString("summary")));
                o.put("date", st.getString("date"));
                o.put("birth_year", Birthdays.birthYear(e.optString("summary"), e.optString("description"), maxYear));
                items.put(o);
            }
            page = resp.optString("nextPageToken", null);
        } while (page != null && !page.isEmpty() && ++pages < MAX_PAGES);
        return result;
    }

    /** Ids of the event series in this calendar that repeat yearly (singleEvents=false). */
    private Set<String> yearlySeries(String base) throws IOException, JSONException {
        Set<String> yearly = new HashSet<String>();
        String page = null;
        int pages = 0;
        do {
            JSONObject resp = getJson(base + "&singleEvents=false" + (page != null ? "&pageToken=" + enc(page) : ""));
            JSONArray evs = resp.optJSONArray("items");
            for (int k = 0; evs != null && k < evs.length(); k++) {
                JSONObject e = evs.getJSONObject(k);
                JSONArray rec = e.optJSONArray("recurrence");
                String[] r = new String[rec == null ? 0 : Math.min(rec.length(), 20)];
                for (int j = 0; j < r.length; j++) r[j] = rec.optString(j);
                if (Birthdays.isYearly(r)) yearly.add(e.optString("id"));
            }
            page = resp.optString("nextPageToken", null);
        } while (page != null && !page.isEmpty() && ++pages < MAX_PAGES);
        return yearly;
    }

    // ------------------------------------------------------------------ weather

    private JSONObject fetchWeather() throws IOException, JSONException {
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + LAT + "&longitude=" + LON
                + "&current=temperature_2m,weather_code,is_day"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                + "&timezone=Europe%2FAmsterdam&forecast_days=5";
        HttpsURLConnection c = open(url);
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new IOException("HTTP " + code);
        }
        JSONObject d = new JSONObject(read(c));
        JSONObject cur = d.getJSONObject("current");
        JSONObject daily = d.getJSONObject("daily");
        JSONArray days = new JSONArray();
        JSONArray time = daily.getJSONArray("time");
        for (int i = 0; i < time.length(); i++) {
            int wcode = daily.getJSONArray("weather_code").optInt(i);
            JSONObject o = new JSONObject();
            o.put("date", time.getString(i));
            o.put("code", wcode);
            o.put("text", wmo(wcode));
            o.put("max", daily.getJSONArray("temperature_2m_max").optDouble(i));
            o.put("min", daily.getJSONArray("temperature_2m_min").optDouble(i));
            o.put("rain_pct", daily.getJSONArray("precipitation_probability_max").optInt(i, -1));
            days.put(o);
        }
        JSONObject w = new JSONObject();
        w.put("location", LOCATION);
        w.put("temp", cur.optDouble("temperature_2m"));
        w.put("code", cur.optInt("weather_code"));
        w.put("text", wmo(cur.optInt("weather_code")));
        w.put("observed", cur.optString("time"));
        w.put("is_day", cur.optInt("is_day", 1) != 0);
        w.put("days", days);
        return w;
    }

    /** WMO weather interpretation codes as documented by Open-Meteo. */
    private static String wmo(int c) {
        switch (c) {
            case 0: return "Helder";
            case 1: return "Overwegend helder";
            case 2: return "Half bewolkt";
            case 3: return "Bewolkt";
            case 45: return "Mist";
            case 48: return "Rijpmist";
            case 51: return "Lichte motregen";
            case 53: return "Motregen";
            case 55: return "Dichte motregen";
            case 56: return "Lichte ijzel";
            case 57: return "IJzel";
            case 61: return "Lichte regen";
            case 63: return "Regen";
            case 65: return "Zware regen";
            case 66: return "Lichte ijsregen";
            case 67: return "IJsregen";
            case 71: return "Lichte sneeuw";
            case 73: return "Sneeuw";
            case 75: return "Zware sneeuw";
            case 77: return "Korrelsneeuw";
            case 80: return "Lichte buien";
            case 81: return "Buien";
            case 82: return "Zware buien";
            case 85: return "Sneeuwbuien";
            case 86: return "Zware sneeuwbuien";
            case 95: return "Onweer";
            case 96: return "Onweer met hagel";
            case 99: return "Zwaar onweer met hagel";
            default: return "Onbekend";
        }
    }

    // ------------------------------------------------------------------ helpers

    private HttpsURLConnection open(String url) throws IOException {
        HttpsURLConnection c = (HttpsURLConnection) new URL(url).openConnection();
        c.setSSLSocketFactory(tls);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        return c;
    }

    /** Reads the response body, refusing anything larger than MAX_RESPONSE_BYTES. */
    private static String read(HttpURLConnection c) throws IOException {
        InputStream in = c.getInputStream();
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) {
                if (buf.size() + n > MAX_RESPONSE_BYTES) throw new IOException("response too large");
                buf.write(b, 0, n);
            }
            return buf.toString("UTF-8");
        } finally {
            in.close();
            c.disconnect();
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** All-day "2026-11-03" -> local midnight with offset, the format the view parses. */
    private static String dateToIso(String date, TimeZone tz) throws JSONException {
        long ms = TimeUtil.parseDate(date, tz);
        if (ms == 0) throw new JSONException("bad all-day date: " + date);
        return TimeUtil.formatLocal(ms, tz);
    }

    /**
     * System trust store plus the ISRG (Let's Encrypt) roots bundled in res/raw. Android 6 predates
     * ISRG Root X1, which Open-Meteo's certificate chains to. System roots are checked first.
     */
    private static SSLSocketFactory trustWithIsrgRoots(Context ctx) throws Exception {
        TrustManagerFactory sysF = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        sysF.init((KeyStore) null);
        final X509TrustManager sys = (X509TrustManager) sysF.getTrustManagers()[0];

        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        int[] ids = {R.raw.isrg_root_x1, R.raw.isrg_root_x2};
        for (int i = 0; i < ids.length; i++) {
            InputStream in = ctx.getResources().openRawResource(ids[i]);
            ks.setCertificateEntry("isrg" + i, cf.generateCertificate(in));
            in.close();
        }
        TrustManagerFactory isrgF = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        isrgF.init(ks);
        final X509TrustManager isrg = (X509TrustManager) isrgF.getTrustManagers()[0];

        X509TrustManager both = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                sys.checkClientTrusted(chain, auth);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                try {
                    sys.checkServerTrusted(chain, auth);
                } catch (CertificateException e) {
                    isrg.checkServerTrusted(chain, auth);  // full chain validation against ISRG roots
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return sys.getAcceptedIssuers();
            }
        };
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, new TrustManager[]{both}, null);
        return sc.getSocketFactory();
    }
}
