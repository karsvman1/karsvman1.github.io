package nl.japandi.dashboard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the dashboard after a reboot when it is not (yet) the default launcher. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Intent i = new Intent(context, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
    }
}
