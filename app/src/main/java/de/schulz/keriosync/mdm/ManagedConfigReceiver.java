package de.schulz.keriosync.mdm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Reagiert auf Änderungen der Managed Configurations (MDM App Restrictions).
 *
 * Broadcast:
 * - android.intent.action.APPLICATION_RESTRICTIONS_CHANGED
 */
public class ManagedConfigReceiver extends BroadcastReceiver {

    private static final String TAG = "KerioManagedCfgRcvr";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null)
            return;

        String action = intent.getAction();
        if (action == null)
            return;

        if (!Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED.equals(action)) {
            return;
        }

        Log.i(TAG, "APPLICATION_RESTRICTIONS_CHANGED -> apply managed config");
        ManagedConfig.applyToAllAccountsIfChanged(context);
    }
}
