/**
 * @file ManagedConfigReceiver.java
 * @brief BroadcastReceiver für MDM-Änderungen an Managed Configurations
 *
 * @author Simon Marcel Linden
 * @date 2026
 * @version 0.9.8
 */
package de.schulz.keriosync.mdm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * @class ManagedConfigReceiver
 * @brief Reagiert auf Änderungen der Managed Configurations (App Restrictions)
 *
 *        Dieser BroadcastReceiver lauscht auf den System-Broadcast
 *        ACTION_APPLICATION_RESTRICTIONS_CHANGED und stößt bei Änderungen das
 *        erneute
 *        Anwenden der MDM-Konfiguration an.
 *
 * @see android.content.Intent#ACTION_APPLICATION_RESTRICTIONS_CHANGED
 * @see ManagedConfig#applyToAllAccountsIfChanged(Context)
 */
public class ManagedConfigReceiver extends BroadcastReceiver {

    /**
     * @brief Log-Tag für diesen Receiver
     */
    private static final String TAG = "KerioManagedCfgRcvr";

    @Override
    /**
     * @brief Reagiert auf den Broadcast ACTION_APPLICATION_RESTRICTIONS_CHANGED
     *
     *        Prüft den eingehenden Intent und triggert die Anwendung der Managed
     *        Config, sofern sich App-Restrictions geändert haben.
     *
     * @param context Anwendungskontext
     * @param intent  Eingehender Broadcast-Intent
     */
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
