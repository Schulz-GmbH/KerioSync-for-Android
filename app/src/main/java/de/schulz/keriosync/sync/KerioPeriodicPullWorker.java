/**
 * @file    KerioPeriodicPullWorker.java
 * @brief   WorkManager-Worker für zuverlässige periodische Calendar/Contact-Pull-Synchronisation
 * @author  Kerio Sync Team
 * @date    2025
 * @version 1.0
 */

package de.schulz.keriosync.sync;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import de.schulz.keriosync.auth.KerioAccountConstants;

/**
 * @class KerioPeriodicPullWorker
 * @brief WorkManager-Worker für zuverlässigen periodischen Server->Client Pull
 *
 *        **Funktion:**
 *        - Fallback-Mechanismus für periodische Synchronisation via WorkManager
 *        - Wird von KerioSyncScheduler periodisch geplant
 *        - Triggert ContentResolver.requestSync() für konfigurierte Accounts
 *        - Beachtet Account-Einstellung "periodic_sync_enabled"
 *
 *        **WorkManager vs. JobScheduler:**
 *        - WorkManager: Android 5.0+, bessere Kompatibilität, Batching
 *        - JobScheduler: Android 5.0+, aber OEM-Suppression auf Samsung etc.
 *        - KerioSync nutzt beide für maximale Zuverlässigkeit
 *
 *        **Ablauf:**
 *        1. Extrahiert InputData (optionale Account-Spezifikation)
 *        2. Falls InputData: Verwendet nur diesen Account
 *        3. Falls nicht: Triggert alle Kerio-Accounts mit periodischem Sync
 *        enabled
 *        4. Setzt SYNC_EXTRAS_MANUAL + SYNC_EXTRAS_EXPEDITED für höhere
 *        Priorität
 *        5. Gibt Result.success() oder Result.retry() zurück
 *
 *        **Retry-Verhalten:**
 *        - Result.success(): Keine Wiederholung
 *        - Result.retry(): WorkManager plant erneut (bacoff-Strategie)
 */
public class KerioPeriodicPullWorker extends Worker {

    private static final String TAG = "KerioPeriodicPullWorker";

    public KerioPeriodicPullWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /**
     * @brief Wird vom WorkManager periodisch aufgerufen
     *
     *        **Ablauf:**
     *        1. Extrahiert optionale InputData (account_name, account_type)
     *        2. Falls InputData vorhanden: Triggert nur diesen Account
     *        3. Falls nicht vorhanden: Sucht alle Kerio-Accounts
     *        4. Validiert jeden Account (type muss ACCOUNT_TYPE entsprechen)
     *        5. Prüft Account-Setting "periodic_sync_enabled" (default: true)
     *        6. Ruft ContentResolver.requestSync() mit MANUAL + EXPEDITED auf
     *        7. Gibt Result.success() oder Result.retry() zurück
     *
     *        **Fehlerbehandlung:**
     *        - Bei Exception: Result.retry() (WorkManager plant erneut mit Backoff)
     *        - Bei fehlenden/ungültigen Accounts: ignorieren und fortfahren
     *
     * @return Result.success() bei erfolgreicher Arbeit; Result.retry() bei Fehler
     */
    @NonNull
    @Override
    public Result doWork() {
        try {
            Context ctx = getApplicationContext();
            AccountManager am = AccountManager.get(ctx);

            String accountName = getInputData().getString(KerioSyncScheduler.WM_KEY_ACCOUNT_NAME);
            String accountType = getInputData().getString(KerioSyncScheduler.WM_KEY_ACCOUNT_TYPE);

            // Fallback: falls InputData fehlt -> alle Accounts triggern
            Account[] targets;
            if (accountName != null && accountType != null) {
                targets = new Account[] { new Account(accountName, accountType) };
                Log.i(TAG, "doWork(): Trigger für 1 Account aus InputData: " + accountName);
            } else {
                targets = am.getAccountsByType(KerioAccountConstants.ACCOUNT_TYPE);
                Log.i(TAG, "doWork(): InputData fehlt -> Trigger für " + targets.length + " Kerio-Accounts");
            }

            for (Account acc : targets) {
                if (!KerioAccountConstants.ACCOUNT_TYPE.equals(acc.type)) {
                    Log.w(TAG, "doWork(): skip non-kerio type=" + acc.type);
                    continue;
                }

                // Nur wenn periodischer Sync aktiv ist
                String enabledStr = am.getUserData(acc, KerioAccountConstants.KEY_PERIODIC_SYNC_ENABLED);
                boolean enabled = (enabledStr == null) || "1".equals(enabledStr);
                if (!enabled) {
                    Log.i(TAG, "doWork(): periodic disabled für " + acc.name);
                    continue;
                }

                Bundle extras = new Bundle();
                extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
                extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);

                ContentResolver.requestSync(acc, KerioAccountConstants.CALENDAR_AUTHORITY, extras);
                Log.i(TAG, "doWork(): requestSync() -> " + acc.name);
            }

            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "doWork() Fehler: " + e.getMessage(), e);
            return Result.retry();
        }
    }
}
