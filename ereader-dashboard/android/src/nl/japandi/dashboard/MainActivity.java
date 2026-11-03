package nl.japandi.dashboard;

import android.app.Activity;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Full-screen dashboard and Home screen. Redraws once per minute (clock) and after each sync.
 *
 * Configuration (Google credentials, optional server URL, rotation) is written only by
 * {@link ConfigReceiver}; this activity is exported as Home app and therefore ignores intent
 * extras. It reloads when the stored config_version changes.
 */
public class MainActivity extends Activity {
    private static final long SYNC_INTERVAL_MS = 10 * 60 * 1000L;
    private static final long RETRY_INTERVAL_MS = 30 * 1000L;
    private static final long PARTIAL_RETRY_MS = 2 * 60 * 1000L;
    private static final String CACHE_FILE = "dashboard.json";
    /** Bound for the cache file and a server response (the fetcher bounds each Google response). */
    private static final int MAX_READ_BYTES = 4 * 1024 * 1024;

    private final Handler handler = new Handler();
    private DashboardView view;
    private SharedPreferences prefs;
    private PowerManager.WakeLock wakeLock;
    private WifiManager wifi;
    private WifiManager.WifiLock wifiLock;
    private volatile boolean syncing;
    /** Used only from the sync worker; replaced (never mutated) on the UI thread between syncs. */
    private volatile DataFetcher fetcher;
    private int configVersion = -1;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            view.redraw();
            // Align to the next whole minute so the clock flips exactly once.
            long now = System.currentTimeMillis();
            handler.postDelayed(this, 60000L - (now % 60000L) + 200L);
        }
    };

    private final Runnable sync = new Runnable() {
        @Override
        public void run() {
            startSync();
        }
    };

    private final BroadcastReceiver netReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            NetworkInfo ni = ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).getActiveNetworkInfo();
            if (ni != null && ni.isConnected()) {
                handler.removeCallbacks(sync);
                handler.post(sync);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences(ConfigReceiver.PREFS, MODE_PRIVATE);
        view = new DashboardView(this);
        setContentView(view);
        reloadConfigIfChanged();

        // Partial wake lock so the clock and sync keep running while the screen is static.
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dashboard:tick");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();

        // Keep wifi on and awake: nothing else on this device switches it on any more.
        wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL, "dashboard:wifi");
        wifiLock.setReferenceCounted(false);
        wifiLock.acquire();
        ensureWifi();
        // Sync as soon as the network comes up instead of waiting for the next retry.
        registerReceiver(netReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));

        loadCache();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Extras are ignored on purpose (see class comment); only stored config counts.
        if (reloadConfigIfChanged()) {
            handler.removeCallbacks(sync);
            handler.post(sync);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(tick);
        handler.removeCallbacks(sync);
        handler.post(tick);
        handler.post(sync);
    }

    @Override
    protected void onPause() {
        // Keep running: this is a dedicated display. Only the Android system stops us.
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        unregisterReceiver(netReceiver);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Dedicated dashboard: ignore Back.
    }

    /** Page keys (declared by this reader's rk29-keypad layout) and volume keys page the tasks. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                view.pageTasks(1);
                return true;
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_VOLUME_UP:
                view.pageTasks(-1);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    /** Applies stored settings when ConfigReceiver changed them. Returns true on a change. */
    private boolean reloadConfigIfChanged() {
        int v = prefs.getInt(ConfigReceiver.VERSION, 0);
        if (v == configVersion) return false;
        configVersion = v;
        view.setLandscapeRotation(prefs.getInt("rotation", 90));
        fetcher = null;  // rebuilt with the current credentials on the next sync
        return true;
    }

    private void ensureWifi() {
        try {
            if (!wifi.isWifiEnabled()) wifi.setWifiEnabled(true);
        } catch (SecurityException ignored) {
            // Permission removed by firmware: sync keeps retrying with cached data on screen.
        }
    }

    private void loadCache() {
        try {
            File f = new File(getFilesDir(), CACHE_FILE);
            if (!f.exists()) return;
            view.setData(new JSONObject(readAll(new FileInputStream(f))), false);
        } catch (Exception ignored) {
            // Corrupt cache: start empty, the next sync replaces it.
        }
    }

    private void startSync() {
        final boolean standalone = prefs.getString("refresh_token", null) != null;
        final String url = prefs.getString("url", null);
        final String key = prefs.getString("key", "");
        if (!standalone && url == null) {
            view.setStatus("Niet geconfigureerd");
            handler.postDelayed(sync, RETRY_INTERVAL_MS);
            return;
        }
        if (syncing) return;
        if (standalone && fetcher == null) {
            try {
                fetcher = new DataFetcher(this, prefs.getString("client_id", ""),
                        prefs.getString("client_secret", ""), prefs.getString("refresh_token", ""));
            } catch (Exception e) {
                view.setStatus("TLS-fout");  // bundled certificates unreadable: cannot sync safely
                handler.postDelayed(sync, PARTIAL_RETRY_MS);
                return;
            }
        }
        syncing = true;
        final JSONObject previous = view.getData();
        final DataFetcher f = fetcher;
        new Thread(new Runnable() {
            @Override
            public void run() {
                JSONObject data = null;
                try {
                    data = standalone ? fetchDirect(f, previous) : fetchFromServer(url, key);
                    if (data != null) {
                        FileOutputStream out = openFileOutput(CACHE_FILE, MODE_PRIVATE);
                        out.write(data.toString().getBytes("UTF-8"));
                        out.close();
                    }
                } catch (Exception ignored) {
                    // Offline or source down: keep showing cached data.
                }
                final JSONObject result = data;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        syncing = false;
                        if (result != null) {
                            view.setData(result, true);
                            JSONArray errors = result.optJSONArray("errors");
                            boolean partial = errors != null && errors.length() > 0;
                            handler.postDelayed(sync, partial ? PARTIAL_RETRY_MS : SYNC_INTERVAL_MS);
                        } else {
                            view.setStatus("Offline");
                            ensureWifi();
                            handler.postDelayed(sync, RETRY_INTERVAL_MS);
                        }
                    }
                });
            }
        }).start();
    }

    /** Standalone: fetch Google + weather here. Returns null when no source could be reached. */
    private static JSONObject fetchDirect(DataFetcher f, JSONObject previous) throws Exception {
        JSONObject d = f.build(previous);
        return f.wasUnreachable() ? null : d;
    }

    private static JSONObject fetchFromServer(String url, String key) throws Exception {
        String sep = url.indexOf('?') >= 0 ? "&" : "?";
        HttpURLConnection c = (HttpURLConnection) new URL(url + sep + "key=" + key).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        try {
            return c.getResponseCode() == 200 ? new JSONObject(readAll(c.getInputStream())) : null;
        } finally {
            c.disconnect();
        }
    }

    /** Reads a stream (cache file or server response), refusing more than MAX_READ_BYTES. */
    private static String readAll(InputStream in) throws java.io.IOException {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) {
                if (buf.size() + n > MAX_READ_BYTES) throw new java.io.IOException("input too large");
                buf.write(b, 0, n);
            }
            return buf.toString("UTF-8");
        } finally {
            in.close();
        }
    }
}
