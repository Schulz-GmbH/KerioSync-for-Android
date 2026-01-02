/**
 * @file KerioAccountMigrator.java
 * @brief Migrationstool für bestehende Kerio-Accounts (alte Versionen -> neue UserData-Keys)
 *
 * @author Simon Marcel Linden
 * @date 2026
 * @version 0.9.8
 */
package de.schulz.keriosync.sync;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import de.schulz.keriosync.auth.KerioAccountConstants;

/**
 * @class KerioAccountMigrator
 * @brief Migriert/aktualisiert bestehende Kerio-Accounts mit fehlenden
 *        Sync-Settings.
 *        Accounts aus älteren App-Versionen besitzen oft noch keine
 *        UserData-Keys
 *        für Periodic-/Instant-Sync-Einstellungen. Diese werden hier mit
 *        sinnvollen
 *        Defaults befüllt, damit Sync ohne UI-Eingabe funktioniert.
 *        Wird beim Service-Start und Boot/Update automatisch ausgeführt.
 */
public final class KerioAccountMigrator {

    private static final String TAG = "KerioAccountMigrator";

    /**
     * @brief Privater Konstruktor (Utility-Klasse).
     */
    private KerioAccountMigrator() {
    }

    /**
     * @brief Migriert alle Kerio-Accounts: setzt fehlende UserData-Defaults und
     *        wendet Scheduler an.
     * @param context App-Kontext
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
            /**
             * @brief Migriert einen einzelnen Account: Setzt fehlende UserData-Keys und
             *        wendet Scheduler an.
             * @param context App-Kontext
             * @param am      AccountManager
             * @param account Zu migrierender Kerio-Account
             */
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
