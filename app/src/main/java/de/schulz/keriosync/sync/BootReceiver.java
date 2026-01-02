/**
 * @file BootReceiver.java
 * @brief BroadcastReceiver zur Wiederherstellung der Sync-Scheduler nach Boot/Update
 *
 * @author Simon Marcel Linden
 * @date 2026
 * @version 0.9.8
 */
package de.schulz.keriosync.sync;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import de.schulz.keriosync.auth.KerioAccountConstants;

/**
 * @class BootReceiver
 * @brief Stellt nach Geräteneust oder App-Update die Sync-Scheduler wieder her.
 *        Reagiert auf BOOT_COMPLETED, LOCKED_BOOT_COMPLETED und
 *        MY_PACKAGE_REPLACED,
 *        um für alle Kerio-Accounts die Periodic-Sync- und
 *        Instant-Job-Konfiguration
 *        erneut anzuwenden (manche ROMs verlieren diese Einstellungen).
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "KerioBootReceiver";

    /**
     * @brief Empfängt Boot/Update-Broadcasts und wendet Sync-Scheduler erneut an.
     * @param context App-Kontext
     * @param intent  Broadcast-Intent (ACTION_BOOT_COMPLETED,
     *                ACTION_LOCKED_BOOT_COMPLETED
     *                oder ACTION_MY_PACKAGE_REPLACED)
     */
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null)
            return;

        String action = intent.getAction();

        boolean relevant = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);

        if (!relevant)
            return;

        Log.i(TAG, "BootReceiver: " + action + " -> re-apply scheduler for all Kerio accounts");

        AccountManager am = AccountManager.get(context);
        Account[] accounts = am.getAccountsByType(KerioAccountConstants.ACCOUNT_TYPE);

        for (Account a : accounts) {
            KerioSyncScheduler.applyAll(context, a);
        }
    }
}
