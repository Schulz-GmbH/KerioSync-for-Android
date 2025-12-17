// ===== app/src/main/java/de/schulz/keriosync/sync/KerioAccountMigrator.java =====
package de.schulz.keriosync.sync;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import de.schulz.keriosync.auth.KerioAccountConstants;

/**
 * Migriert/aktualisiert bestehende KerioSync-Accounts, die in älteren App-Versionen
 * angelegt wurden und noch keine neuen UserData-Keys (Sync Settings) besitzen.
 *
 * Wichtig:
 * - Wird beim Service-Start und Boot/Update ausgeführt,
 *   damit Periodic Sync auch ohne UI korrekt gesetzt wird.
 */
public final class KerioAccountMigrator {

    private static final String TAG = "KerioAccountMigrator";

    private KerioAccountMigrator() { }

    /**
     * Setzt fehlende Default-Keys für alle Kerio-Accounts und wendet Scheduler an.
     */
    public static void migrateAllAccounts(Context context) {
        try {
            AccountManager am = AccountManager.get(context);
            Account[] accounts = am.getAccountsByType(KerioAccountConstants.ACCOUNT_TYPE);

            Log.i(TAG, "migrateAllAccounts(): Kerio-Accounts gefunden: " + accounts.length);

            for (Account account : accounts) {
                migrateAccount(context, am, account);
            }
        } catch (Exception e) {
            Log.e(TAG, "migrateAllAccounts() Fehler: " + e.getMessage(), e);
        }
    }

    private static void migrateAccount(Context context, AccountManager am, Account account) {
        boolean changed = false;

        // ---------------------------------------------------------------------
        // Periodic Defaults (entscheidend für Server -> Client Pull)
        // ---------------------------------------------------------------------
        String periodicEnabled = am.getUserData(account, KerioAccountConstants.KEY_PERIODIC_SYNC_ENABLED);
        if (periodicEnabled == null) {
            am.setUserData(account, KerioAccountConstants.KEY_PERIODIC_SYNC_ENABLED,
                    KerioSyncScheduler.DEFAULT_PERIODIC_ENABLED ? "1" : "0");
            changed = true;
            Log.i(TAG, "migrateAccount(): set default KEY_PERIODIC_SYNC_ENABLED für " + account.name);
        }

        String interval = am.getUserData(account, KerioAccountConstants.KEY_SYNC_INTERVAL_MINUTES);
        if (TextUtils.isEmpty(interval)) {
            am.setUserData(account, KerioAccountConstants.KEY_SYNC_INTERVAL_MINUTES,
                    String.valueOf(KerioSyncScheduler.DEFAULT_PERIODIC_MINUTES));
            changed = true;
            Log.i(TAG, "migrateAccount(): set default KEY_SYNC_INTERVAL_MINUTES für " + account.name);
        }

        // ---------------------------------------------------------------------
        // Instant Defaults (nur fürs „quasi sofort“)
        // ---------------------------------------------------------------------
        String instantEnabled = am.getUserData(account, KerioAccountConstants.KEY_INSTANT_SYNC_ENABLED);
        if (instantEnabled == null) {
            am.setUserData(account, KerioAccountConstants.KEY_INSTANT_SYNC_ENABLED,
                    KerioSyncScheduler.DEFAULT_INSTANT_ENABLED ? "1" : "0");
            changed = true;
            Log.i(TAG, "migrateAccount(): set default KEY_INSTANT_SYNC_ENABLED für " + account.name);
        }

        String upd = am.getUserData(account, KerioAccountConstants.KEY_INSTANT_SYNC_UPDATE_DELAY_SECONDS);
        if (TextUtils.isEmpty(upd)) {
            am.setUserData(account, KerioAccountConstants.KEY_INSTANT_SYNC_UPDATE_DELAY_SECONDS,
                    String.valueOf(KerioSyncScheduler.DEFAULT_INSTANT_UPDATE_DELAY_SECONDS));
            changed = true;
            Log.i(TAG, "migrateAccount(): set default KEY_INSTANT_SYNC_UPDATE_DELAY_SECONDS für " + account.name);
        }

        String max = am.getUserData(account, KerioAccountConstants.KEY_INSTANT_SYNC_MAX_DELAY_SECONDS);
        if (TextUtils.isEmpty(max)) {
            am.setUserData(account, KerioAccountConstants.KEY_INSTANT_SYNC_MAX_DELAY_SECONDS,
                    String.valueOf(KerioSyncScheduler.DEFAULT_INSTANT_MAX_DELAY_SECONDS));
            changed = true;
            Log.i(TAG, "migrateAccount(): set default KEY_INSTANT_SYNC_MAX_DELAY_SECONDS für " + account.name);
        }

        // Scheduler anwenden (WorkManager-Periodic wird jetzt NICHT mehr resettet)
        KerioSyncScheduler.applyAll(context, account);

        Log.i(TAG, "migrateAccount(): done for " + account.name + ", changed=" + changed);
    }
}
