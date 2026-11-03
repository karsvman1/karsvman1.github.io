package nl.japandi.dashboard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * The only way to change the dashboard configuration. The manifest protects this receiver with
 * android.permission.WRITE_SECURE_SETTINGS (signature|privileged|development): the ADB shell
 * holds it, ordinary apps cannot obtain it. So another app on the reader cannot redirect the
 * sync or replace the Google credentials, although the Home activity itself is exported.
 *
 *   adb shell am broadcast -a nl.japandi.dashboard.CONFIGURE -n nl.japandi.dashboard/.ConfigReceiver \
 *       --es client_id ID --es client_secret SECRET --es refresh_token TOKEN --ei rotation 90
 *
 * Optional server mode instead of standalone: --es url http://HOST:8765/dashboard.json --es key KEY
 */
public class ConfigReceiver extends BroadcastReceiver {
    static final String ACTION = "nl.japandi.dashboard.CONFIGURE";
    static final String PREFS = "dashboard";
    static final String VERSION = "config_version";
    private static final String[] STRING_KEYS = {"url", "key", "client_id", "client_secret", "refresh_token"};

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor e = prefs.edit();
        int changed = 0;
        for (int i = 0; i < STRING_KEYS.length; i++) {
            String v = intent.getStringExtra(STRING_KEYS[i]);
            if (v != null) {
                e.putString(STRING_KEYS[i], v);
                changed++;
            }
        }
        int rotation = intent.getIntExtra("rotation", 0);
        if (rotation == 90 || rotation == 270) {
            e.putInt("rotation", rotation);
            changed++;
        }
        if (changed == 0) {
            setResultData("no settings in broadcast");
            return;
        }
        e.putInt(VERSION, prefs.getInt(VERSION, 0) + 1);
        if (!e.commit()) {
            setResultData("failed to save settings");
            return;
        }
        setResultData("saved " + changed + " setting(s)");
        Intent show = new Intent(context, MainActivity.class);
        show.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(show);  // MainActivity sees the new config_version and resyncs
    }
}
