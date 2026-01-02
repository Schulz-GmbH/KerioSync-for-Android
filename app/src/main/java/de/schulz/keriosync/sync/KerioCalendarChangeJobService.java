/**
 * @file KerioCalendarChangeJobService.java
 * @brief JobService für Instant-Sync bei Änderungen im CalendarProvider
 *
 * @author Simon Marcel Linden
 * @date 2026
 * @version 0.9.8
 */
package de.schulz.keriosync.sync;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.PersistableBundle;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import de.schulz.keriosync.auth.KerioAccountConstants;

/**
 * @class KerioCalendarChangeJobService
 * @brief Reagiert auf Änderungen im CalendarProvider via TriggerContentUri.
 *
 *        Nutzt TriggerContentUri (Content Observer) um bei lokalen
 *        Kalenderänderungen
 *        (Client -> Server) einen expedited Sync auszulösen. Verzögerung und
 *        Max-Delay
 *        sind konfigurierbar über UserData-Keys (Instant Sync Settings).
 */
public class KerioCalendarChangeJobService extends JobService {

    private static final String TAG = "KerioCalChangeJob";

    private static final String EXTRA_ACCOUNT_NAME = "account_name";
    private static final String EXTRA_ACCOUNT_TYPE = "account_type";

    /**
     * @brief Job-Start: Liest Account-Daten und löst expedited Sync aus.
     * @param params Job-Parameter mit Account-Info
     * @return false (Job ist sofort fertig)
     */
    @Override
    public boolean onStartJob(JobParameters params) {
        try {
            PersistableBundle extras = params.getExtras();
            String accountName = extras.getString(EXTRA_ACCOUNT_NAME, null);
            String accountType = extras.getString(EXTRA_ACCOUNT_TYPE, null);

            if (accountName == null || accountType == null) {
                Log.w(TAG, "onStartJob(): Keine Account-Daten im Job.");
                jobFinished(params, false);
                return false;
            }

            Account account = new Account(accountName, accountType);

            Log.i(TAG, "onStartJob(): CalendarProvider Änderung -> expedited Sync: " + accountName);

            KerioSyncScheduler.requestExpeditedSync(account);

            jobFinished(params, false);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "onStartJob() Fehler: " + e.getMessage(), e);
            jobFinished(params, true);
            return false;
        }
    }

    /**
     * @brief Job-Stop: wird vom System aufgerufen, wenn Job vorzeitig abgebrochen
     *        wird.
     * @param params Job-Parameter
     * @return true (Job soll neu gestartet werden)
     */
    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    /**
     * @brief Plant oder storniert den Instant-Sync Job für einen Account.
     * @param context App-Kontext
     * @param account Zu konfigurierender Kerio-Account
     */
    public static void scheduleOrCancelForAccount(Context context, Account account) {
        AccountManager am = AccountManager.get(context);

        boolean instantEnabled = "1".equals(am.getUserData(account, KerioAccountConstants.KEY_INSTANT_SYNC_ENABLED));

        int updateDelaySec = parseIntOrDefault(
                am.getUserData(account, KerioAccountConstants.KEY_INSTANT_SYNC_UPDATE_DELAY_SECONDS),
                KerioSyncScheduler.DEFAULT_INSTANT_UPDATE_DELAY_SECONDS);

        int maxDelaySec = parseIntOrDefault(
                am.getUserData(account, KerioAccountConstants.KEY_INSTANT_SYNC_MAX_DELAY_SECONDS),
                KerioSyncScheduler.DEFAULT_INSTANT_MAX_DELAY_SECONDS);

        if (updateDelaySec < 1)
            updateDelaySec = 1;
        if (maxDelaySec < updateDelaySec)
            maxDelaySec = updateDelaySec;

        int jobId = stableJobIdForAccount(account);

        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) {
            Log.w(TAG, "JobScheduler nicht verfügbar.");
            return;
        }

        if (!instantEnabled) {
            scheduler.cancel(jobId);
            Log.i(TAG, "InstantSync deaktiviert -> Job gecancelt. jobId=" + jobId);
            return;
        }

        ComponentName cn = new ComponentName(context, KerioCalendarChangeJobService.class);

        PersistableBundle pExtras = new PersistableBundle();
        pExtras.putString(EXTRA_ACCOUNT_NAME, account.name);
        pExtras.putString(EXTRA_ACCOUNT_TYPE, account.type);

        Uri eventsUri = KerioSyncScheduler.getCalendarEventsUri();

        JobInfo.Builder b = new JobInfo.Builder(jobId, cn)
                // WICHTIG: NICHT setPersisted(true) mit TriggerContentUri
                .setTriggerContentUpdateDelay(updateDelaySec * 1000L)
                .setTriggerContentMaxDelay(maxDelaySec * 1000L)
                /**
                 * @brief Parsed einen String-Wert zu int, liefert Default bei Fehler.
                 * @param val Eingabestring
                 * @param def Default-Wert
                 * @return geparster int oder Default
                 */
                .setExtras(pExtras)
                .addTriggerContentUri(
                        new JobInfo.TriggerContentUri(eventsUri, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);

        int res = scheduler.schedule(b.build());
        Log.i(TAG, "InstantSync Job scheduled: res=" + res
                + ", jobId=" + jobId
                + ", updateDelay=" + updateDelaySec + "s"
                + ", maxDelay=" + maxDelaySec + "s"
                + ", uri=" + eventsUri);
    }

    /**
     * @brief Parst einen String zu int, liefert Default bei Fehler.
     * @param val Eingabestring
     * @param def Default-Wert
     * @return geparster int oder Default
     */
    private static int parseIntOrDefault(String val, int def) {
        try {
            if (val == null)
                return def;
            /**
             * @brief Erzeugt eine stabile Job-ID basierend auf Account-Name und -Typ
             *        (CRC32).
             * @param account Kerio-Account
             * @return Job-ID im Bereich 10000..49999
             */
            return Integer.parseInt(val.trim());
        } catch (Exception ignored) {
            return def;
        }
    }

    /**
     * @brief Erzeugt eine stabile Job-ID basierend auf Account-Name und -Typ
     *        (CRC32).
     * @param account Kerio-Account
     * @return Job-ID im Bereich 10000..49999
     */
    private static int stableJobIdForAccount(Account account) {
        CRC32 crc = new CRC32();
        crc.update((account.type + "|" + account.name).getBytes(StandardCharsets.UTF_8));
        long v = crc.getValue();
        return (int) (10000 + (v % 40000));
    }
}
