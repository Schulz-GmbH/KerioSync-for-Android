package de.schulz.keriosync.sync;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import de.schulz.keriosync.auth.KerioAccountConstants;

/**
 * Nach Reboot: Periodic Sync & Instant Job erneut anwenden.
 * Zusätzlich nach App-Update (MY_PACKAGE_REPLACED) erneut anwenden,
 * weil einige ROMs Periodic/Jobs verlieren.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "KerioBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        String action = intent.getAction();

        boolean relevant =
                Intent.ACTION_BOOT_COMPLETED.equals(action)
                        || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                        || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);

        if (!relevant) return;

        Log.i(TAG, "BootReceiver: " + action + " -> re-apply scheduler for all Kerio accounts");

        AccountManager am = AccountManager.get(context);
        Account[] accounts = am.getAccountsByType(KerioAccountConstants.ACCOUNT_TYPE);

        for (Account a : accounts) {
            KerioSyncScheduler.applyAll(context, a);
        }
    }
}
