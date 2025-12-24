package de.schulz.keriosync.sync;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.util.Log;

import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

import de.schulz.keriosync.auth.KerioAccountConstants;

/**
 * Zentrale Stelle um Periodic Sync + Instant Sync Trigger zu konfigurieren.
 *
 * Periodic Pull:
 * - robust über WorkManager (ohne WRITE_SYNC_SETTINGS)
 *
 * Instant Sync:
 * - JobScheduler TriggerContentUri (nicht persisted!), Reschedule über
 * BootReceiver
 */
public final class KerioSyncScheduler {

    private static final String TAG = "KerioSyncScheduler";

    public static final String CALENDAR_AUTHORITY = "com.android.calendar";

    /** Contacts Provider Authority */
    public static final String CONTACTS_AUTHORITY = "com.android.contacts";

    /** Android-/WorkManager-Minimum für Periodic */
    public static final int MIN_PERIODIC_MINUTES = 15;

    /** Defaults (Periodic) */
    public static final int DEFAULT_PERIODIC_MINUTES = 15;
    public static final boolean DEFAULT_PERIODIC_ENABLED = true;

    /** Defaults (Instant Sync / Change Trigger) */
    public static final boolean DEFAULT_INSTANT_ENABLED = true;
    public static final int DEFAULT_INSTANT_UPDATE_DELAY_SECONDS = 3;
    public static final int DEFAULT_INSTANT_MAX_DELAY_SECONDS = 10;

    /** WorkManager Data Keys */
    public static final String WM_KEY_ACCOUNT_NAME = "wm_account_name";
    public static final String WM_KEY_ACCOUNT_TYPE = "wm_account_type";

    private KerioSyncScheduler() {
    }

    // ---------------------------------------------------------------------
    // Öffentlicher Einstiegspunkt
    // ---------------------------------------------------------------------

    public static void applyAll(Context context, Account account) {
        // 1) Best-effort Sync Settings (kann ohne Permission fehlschlagen, ist ok)
        try {
            ensureSyncableAndAutoBestEffort(account);
        } catch (Exception e) {
            Log.w(TAG, "ensureSyncableAndAutoBestEffort() Fehler: " + e.getMessage(), e);
        }

        // 2) Periodic Pull via WorkManager (SERVER -> CLIENT)
        try {
            applyPeriodicWork(context, account);
        } catch (Exception e) {
            Log.e(TAG, "applyPeriodicWork() Fehler: " + e.getMessage(), e);
        }

        // 3) Instant Trigger Job (CLIENT Änderungen -> Sync)
        try {
            applyInstantSyncJob(context, account);
        } catch (Exception e) {
            Log.e(TAG, "applyInstantSyncJob() Fehler (wird ignoriert): " + e.getMessage(), e);
        }

        // 4) Debug State
        try {
            logStateBestEffort(context, account);
        } catch (Exception e) {
            Log.w(TAG, "logStateBestEffort() Fehler: " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------------
    // Best-effort Sync Framework Flags
    // ---------------------------------------------------------------------

    private static void ensureSyncableAndAutoBestEffort(Account account) {
        try {
            // Kalender
            ContentResolver.setIsSyncable(account, CALENDAR_AUTHORITY, 1);
            ContentResolver.setSyncAutomatically(account, CALENDAR_AUTHORITY, true);

            // Kontakte (damit Contacts-App den Account als Quelle anbietet)
            ContentResolver.setIsSyncable(account, CONTACTS_AUTHORITY, 1);
            ContentResolver.setSyncAutomatically(account, CONTACTS_AUTHORITY, true);

            Log.i(TAG, "ensureSyncableAndAuto(): OK für " + account.name);
        } catch (SecurityException se) {
            // Auf normalen Geräten/Samsung meist nicht erlaubt -> ok, WorkManager übernimmt
            Log.w(TAG, "ensureSyncableAndAuto(): keine Berechtigung (OK, WorkManager übernimmt). " + se.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Periodic Pull (SERVER -> CLIENT) – WorkManager
    // ---------------------------------------------------------------------

    public static void applyPeriodicWork(Context context, Account account) {
        AccountManager am = AccountManager.get(context);

        String enabledStr = am.getUserData(account, KerioAccountConstants.KEY_PERIODIC_SYNC_ENABLED);
        boolean enabled = (enabledStr == null) ? DEFAULT_PERIODIC_ENABLED : "1".equals(enabledStr);

        String intervalStr = am.getUserData(account, KerioAccountConstants.KEY_SYNC_INTERVAL_MINUTES);
        if (intervalStr == null || intervalStr.trim().isEmpty()) {
            intervalStr = String.valueOf(DEFAULT_PERIODIC_MINUTES);
        }

        int minutes;
        try {
            minutes = Integer.parseInt(intervalStr.trim());
        } catch (Exception ignored) {
            minutes = DEFAULT_PERIODIC_MINUTES;
        }

        // WorkManager Minimum erzwingen
        if (minutes < MIN_PERIODIC_MINUTES) {
            minutes = MIN_PERIODIC_MINUTES;
        }

        String workName = stableWorkNameForAccount(account);

        if (!enabled) {
            WorkManager.getInstance(context).cancelUniqueWork(workName);
            Log.i(TAG, "applyPeriodicWork(): deaktiviert -> cancelUniqueWork(" + workName + ")");
            return;
        }

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        Data input = new Data.Builder()
                .putString(WM_KEY_ACCOUNT_NAME, account.name)
                .putString(WM_KEY_ACCOUNT_TYPE, account.type)
                .build();

        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                KerioPeriodicPullWorker.class,
                minutes, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setInputData(input)
                .build();

        // UPDATE/REPLACE: ich nehme UPDATE, damit vorhandene Worker sauber aktualisiert
        // werden
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                workName,
                ExistingPeriodicWorkPolicy.UPDATE,
                req);

        Log.i(TAG, "applyPeriodicWork(): WorkManager periodic gesetzt auf " + minutes + " Minuten, name=" + workName);

        // Optional: initialer Sync direkt nach dem Setzen
        requestManualSync(account, "applyPeriodicWork-initial");
    }

    private static String stableWorkNameForAccount(Account account) {
        CRC32 crc = new CRC32();
        crc.update((account.type + "::" + account.name).getBytes(StandardCharsets.UTF_8));
        long id = crc.getValue();
        return "kerio_periodic_pull_" + id;
    }

    // ---------------------------------------------------------------------
    // Instant Sync Job (Calendar Provider Änderungen -> expedited Sync)
    // ---------------------------------------------------------------------

    public static void applyInstantSyncJob(Context context, Account account) {
        KerioCalendarChangeJobService.scheduleOrCancelForAccount(context, account);
    }

    // ---------------------------------------------------------------------
    // Sync Requests
    // ---------------------------------------------------------------------

    public static void requestManualSync(Account account, String reason) {
        Bundle extras = new Bundle();
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);

        ContentResolver.requestSync(account, CALENDAR_AUTHORITY, extras);
        Log.i(TAG, "requestManualSync(): reason=" + reason + ", account=" + account.name);
    }

    public static void requestExpeditedSync(Account account) {
        Bundle extras = new Bundle();
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);

        ContentResolver.requestSync(account, CALENDAR_AUTHORITY, extras);
        Log.i(TAG, "requestExpeditedSync(): expedited Sync angefordert für " + account.name);
    }

    public static android.net.Uri getCalendarEventsUri() {
        return CalendarContract.Events.CONTENT_URI;
    }

    private static void logStateBestEffort(Context context, Account account) {
        try {
            int syncable = ContentResolver.getIsSyncable(account, CALENDAR_AUTHORITY);
            boolean auto = ContentResolver.getSyncAutomatically(account, CALENDAR_AUTHORITY);
            Log.i(TAG, "STATE: isSyncable=" + syncable + ", syncAutomatically=" + auto);
        } catch (SecurityException se) {
            Log.w(TAG, "STATE: keine Berechtigung: " + se.getMessage());
        }
    }
}
