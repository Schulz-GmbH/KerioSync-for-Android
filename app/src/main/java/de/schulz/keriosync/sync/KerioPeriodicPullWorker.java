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
 * Periodischer Pull-Trigger über WorkManager.
 *
 * Dieser Worker stößt nur an: die eigentliche Arbeit passiert im SyncAdapter.
 */
public class KerioPeriodicPullWorker extends Worker {

    private static final String TAG = "KerioPeriodicPullWorker";

    public KerioPeriodicPullWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

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
                targets = new Account[]{ new Account(accountName, accountType) };
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
