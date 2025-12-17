package de.schulz.keriosync.sync;

import android.accounts.Account;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.PersistableBundle;
import android.util.Log;

import de.schulz.keriosync.auth.KerioAccountConstants;

/**
 * Periodischer JobScheduler-Fallback für Server -> Client Pull.
 *
 * Hintergrund:
 * - addPeriodicSync()/SyncAdapter periodic wird auf vielen OEM ROMs (Samsung etc.)
 *   gebatcht/unterdrückt und kann "scheinbar nie" laufen.
 * - Dieser Job triggert zuverlässig alle X Minuten ContentResolver.requestSync(...).
 *
 * Wichtig:
 * - Der SyncAdapter bleibt die Implementierung (onPerformSync).
 * - Der Job ist nur ein Trigger.
 */
public class KerioPeriodicPullJobService extends JobService {

    private static final String TAG = "KerioPeriodicPullJob";

    static final String EXTRA_ACCOUNT_NAME = "account_name";
    static final String EXTRA_ACCOUNT_TYPE = "account_type";

    @Override
    public boolean onStartJob(JobParameters params) {
        try {
            PersistableBundle extras = params.getExtras();
            String accountName = extras != null ? extras.getString(EXTRA_ACCOUNT_NAME, null) : null;
            String accountType = extras != null ? extras.getString(EXTRA_ACCOUNT_TYPE, null) : null;

            if (accountName == null || accountType == null) {
                Log.w(TAG, "onStartJob(): fehlende Account-Daten. jobId=" + params.getJobId());
                jobFinished(params, false);
                return false;
            }

            if (!KerioAccountConstants.ACCOUNT_TYPE.equals(accountType)) {
                Log.w(TAG, "onStartJob(): falscher accountType=" + accountType);
                jobFinished(params, false);
                return false;
            }

            Account account = new Account(accountName, accountType);

            Log.i(TAG, "PeriodicPullJob -> requestSync() für " + account.name + " / " + account.type
                    + " (jobId=" + params.getJobId() + ")");

            // Wir wollen Pull zuverlässig -> MANUAL + EXPEDITED erhöht die Chance auf Samsung.
            KerioSyncScheduler.requestExpeditedSync(account);

            jobFinished(params, false);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "onStartJob() Fehler: " + e.getMessage(), e);
            jobFinished(params, true);
            return false;
        }
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // Wenn das System abbricht, darf neu geplant werden.
        return true;
    }
}
