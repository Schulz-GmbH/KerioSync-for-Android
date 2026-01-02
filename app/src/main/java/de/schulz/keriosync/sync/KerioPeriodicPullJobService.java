/**
 * @file    KerioPeriodicPullJobService.java
 * @brief   JobService für zuverlässige periodische Calendar/Contact-Pull-Synchronisation
 * @author  Kerio Sync Team
 * @date    2025
 * @version 1.0
 */

package de.schulz.keriosync.sync;

import android.accounts.Account;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.PersistableBundle;
import android.util.Log;

import de.schulz.keriosync.auth.KerioAccountConstants;

/**
 * @class KerioPeriodicPullJobService
 * @brief JobScheduler-Service für zuverlässigen periodischen Server->Client
 *        Pull
 *
 *        **Funktion:**
 *        - Fallback-Mechanismus für periodische Synchronisation (Server ->
 *        Client)
 *        - Triggert alle X Minuten ContentResolver.requestSync() für alle
 *        Kerio-Accounts
 *        - Verhindert Sync-Suppression auf OEM-ROMs (Samsung etc.)
 *
 *        **Hintergrund:**
 *        - addPeriodicSync()/SyncAdapter periodic wird auf vielen OEM-ROMs
 *        gebündelt/unterdrückt
 *        - Kann lange Zeit ohne Ausführung verstreichen
 *        - JobScheduler ermöglicht zuverlässige Trigger mit minimalem
 *        Battery-Impact
 *
 *        **Architektur:**
 *        - JobService ist reiner Trigger (onStartJob/onStopJob)
 *        - Echte Sync-Implementierung bleibt in AbstractThreadedSyncAdapter
 *        - Nutzt KerioSyncScheduler.requestExpeditedSync() mit MANUAL-Flag für
 *        höhere Priorität
 *
 *        **Scheduling:**
 *        - Job wird via KerioSyncScheduler.scheduleOrCancelPeriodicSync()
 *        geplant
 *        - JobId ist account-spezifisch (stable CRC32)
 *        - Extras: EXTRA_ACCOUNT_NAME, EXTRA_ACCOUNT_TYPE
 */
public class KerioPeriodicPullJobService extends JobService {

    private static final String TAG = "KerioPeriodicPullJob";

    static final String EXTRA_ACCOUNT_NAME = "account_name";
    static final String EXTRA_ACCOUNT_TYPE = "account_type";

    /**
     * @brief Wird ausgelöst, wenn der periodische Job vom JobScheduler ausgeführt
     *        wird
     *
     *        **Ablauf:**
     *        1. Extrahiert Account-Informationen aus Job-Extras
     *        (EXTRA_ACCOUNT_NAME, EXTRA_ACCOUNT_TYPE)
     *        2. Validiert Account-Daten und Account-Type
     *        3. Erstellt Account-Objekt
     *        4. Ruft KerioSyncScheduler.requestExpeditedSync() auf
     *        5. Signalisiert Job-Abschluss mit jobFinished(params, false)
     *
     *        **Return-Wert:**
     *        - false: Job wird nicht neu geplant bei Fehler
     *        - Die Neu-Planung obliegt dem Scheduler selbst
     *
     * @param params JobParameters vom JobScheduler (enthält Extras mit
     *               Account-Info)
     * @return false (Neuplaning wird vom Scheduler selbst verwaltet)
     */
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

            // Wir wollen Pull zuverlässig -> MANUAL + EXPEDITED erhöht die Chance auf
            // Samsung.
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
     * @brief Wird aufgerufen, wenn das System den laufenden Job abbricht
     *
     *        **Grund:**
     *        - Geräte-Neustart, Stromsparmodus, oder System-Ressourcen-Mangel
     *        - Ermöglicht JobScheduler, den Job erneut zu planen
     *
     * @param params JobParameters des abgebrochenen Jobs
     * @return true, damit JobScheduler den Job später erneut plant
     */
    @Override
    public boolean onStopJob(JobParameters params) {
        // Wenn das System abbricht, darf neu geplant werden.
        return true;
    }
}
