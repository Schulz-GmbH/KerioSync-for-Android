/**
 * @file    KerioSyncScheduler.java
 * @brief   Zentrale Koordination für alle Synchronisations-Trigger (Periodic + Instant)
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
 * @class KerioSyncScheduler
 * @brief Zentrale Koordinationsstelle für alle Synchronisations-Strategien
 *
 *        **Synchronisations-Modi:**
 *
 *        1. **Periodic Pull (SERVER -> CLIENT):**
 *        - WorkManager-basiert (robust, OEM-kompatibel)
 *        - Kein WRITE_SYNC_SETTINGS Permission nötig
 *        - Minimum 15 Minuten (Android-Limit)
 *        - Account-spezifische WorkManager-Jobs mit stabiler CRC32-ID
 *
 *        2. **Instant Sync (CLIENT -> SERVER):**
 *        - JobScheduler mit TriggerContentUri
 *        - Reagiert auf lokale Änderungen (Add/Update/Delete)
 *        - NICHT persistent - muss nach Boot neu geplant werden (BootReceiver)
 *        - Mit konfigurierbarem Update-Delay und Max-Delay
 *
 *        3. **Sync Framework Flags (Best-Effort):**
 *        - setIsSyncable() + setSyncAutomatically()
 *        - Für UI-Integration (Kontakte-App Quellen-Filter)
 *        - Kann ohne WRITE_SYNC_SETTINGS fehlschlagen - wird ignoriert
 *
 *        **Architektur:**
 *        - applyAll(): Koordinator - wendet alle 3 Modi nacheinander an
 *        - applyPeriodicWork(): WorkManager-Verwaltung
 *        - applyInstantSyncJob(): JobScheduler-Delegation an
 *        KerioCalendarChangeJobService
 *        - requestManualSync() / requestExpeditedSync():
 *        ContentResolver-Trigger
 *        - Alle Methoden sind Exception-tolerant (Fehler werden geloggt, nicht
 *        propagiert)
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

    /**
     * @brief Wendet alle Synchronisations-Strategien nacheinander an
     *
     *        **Ablauf:**
     *        1. ensureSyncableAndAutoBestEffort(): Sync Framework Flags (OK bei
     *        Fehler)
     *        2. applyPeriodicWork(): WorkManager Periodic Pull (ERROR bei Fehler)
     *        3. applyInstantSyncJob(): JobScheduler Instant Sync (ignoriert bei
     *        Fehler)
     *        4. logStateBestEffort(): Debug-Logging (OK bei Fehler)
     *
     *        **Exception-Handling:**
     *        - Alle Exceptions werden geloggt, nicht propagiert
     *        - Fehler in einer Strategie blockieren nicht die anderen
     *        - Ziel: maximale Robustheit / Best-Effort
     *
     * @param context Android Context für WorkManager + AccountManager
     * @param account Kerio-Account für den alle Strategien aktiviert werden
     * @return void
     */
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

    /**
     * @brief Setzt Sync Framework Flags für UI-Integration (Best-Effort)
     *
     *        **Ziel:**
     *        - Kontakte-App soll Account als Quelle in Quellen-Filter anzeigen
     *        - Sync Framework wird über setIsSyncable() + setSyncAutomatically()
     *        informiert
     *
     *        **Authorities:**
     *        - Kalender: com.android.calendar
     *        - Kontakte: com.android.contacts
     *
     *        **Exception-Handling:**
     *        - SecurityException (keine WRITE_SYNC_SETTINGS) wird geloggt, nicht
     *        propagiert
     *        - Auf normalen Geräten/Samsung meist keine Permission
     *        - WorkManager übernimmt Sync-Triggering unabhängig von diesen Flags
     *
     * @param account Kerio-Account
     * @return void
     * @throws SecurityException ohne WRITE_SYNC_SETTINGS (wird ignoriert)
     */
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

    /**
     * @brief Verwaltet WorkManager Periodic Work für Server->Client Pull
     *
     *        **Ablauf:**
     *        1. Liest Account-Settings: periodic_sync_enabled,
     *        sync_interval_minutes
     *        2. Validiert Interval gegen WorkManager-Minimum (15 Minuten)
     *        3. Falls deaktiviert: cancelUniqueWork()
     *        4. Falls aktiviert: enqueueUniquePeriodicWork() mit UPDATE-Policy
     *        5. Triggert initialen Sync direkt nach Setzung
     *
     *        **Constraints:**
     *        - NetworkType.CONNECTED (nur mit Netzwerk)
     *
     *        **WorkManager-Naming:**
     *        - Stabile ID aus CRC32(account_type::account_name)
     *        - UPDATE-Policy: vorhandene Worker werden sauber aktualisiert
     *
     * @param context Android Context für WorkManager
     * @param account Kerio-Account
     * @return void
     */
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

    /**
     * @brief Erzeugt stabile WorkManager-Job-Name aus Account-Daten
     *
     *        **Grund:**
     *        - WorkManager benötigt eindeutige Job-Namen für
     *        enqueueUniquePeriodicWork()
     *        - CRC32 Hash über account_type::account_name garantiert Stabilität
     *        - Name ist deterministisch - gleicher Account hat gleiche ID über
     *        Prozess-Grenzen
     *
     * @param account Kerio-Account
     * @return Stable Job Name (Format: kerio_periodic_pull_<crc32_id>)
     */
    private static String stableWorkNameForAccount(Account account) {
        CRC32 crc = new CRC32();
        crc.update((account.type + "::" + account.name).getBytes(StandardCharsets.UTF_8));
        long id = crc.getValue();
        return "kerio_periodic_pull_" + id;
    }

    // ---------------------------------------------------------------------
    // Instant Sync Job (Calendar Provider Änderungen -> expedited Sync)
    // ---------------------------------------------------------------------

    /**
     * @brief Delegiert Instant Sync Job-Scheduling an KerioCalendarChangeJobService
     *
     *        **Funktion:**
     *        - Wrapper-Methode zur JobScheduler-Konfiguration
     *        - Nutzt TriggerContentUri für sofortige Reaktion auf lokale Änderungen
     *        - NICHT persistent - muss nach Boot neu geplant werden (BootReceiver)
     *
     * @param context Android Context
     * @param account Kerio-Account
     * @return void
     */
    public static void applyInstantSyncJob(Context context, Account account) {
        KerioCalendarChangeJobService.scheduleOrCancelForAccount(context, account);
    }

    // ---------------------------------------------------------------------
    // Sync Requests
    // ---------------------------------------------------------------------

    /**
     * @brief Triggert manuellen Sync mit hoher Priorität
     *
     *        **Flags:**
     *        - SYNC_EXTRAS_MANUAL: true (Benutzer-initiiert)
     *        - SYNC_EXTRAS_EXPEDITED: true (höhere Priorität)
     *
     *        **Use Cases:**
     *        - Benutzer drückt "Jetzt synchronisieren" im UI
     *        - Initialer Sync nach applyPeriodicWork()
     *        - Test-Zwecke
     *
     * @param account Kerio-Account
     * @param reason  Debug-String für Logging (z.B. "user-requested",
     *                "applyPeriodicWork-initial")
     * @return void
     */
    public static void requestManualSync(Account account, String reason) {
        Bundle extras = new Bundle();
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);

        ContentResolver.requestSync(account, CALENDAR_AUTHORITY, extras);
        Log.i(TAG, "requestManualSync(): reason=" + reason + ", account=" + account.name);
    }

    /**
     * @brief Triggert expedited Sync mit manueller Priorität
     *
     *        **Flags:**
     *        - SYNC_EXTRAS_EXPEDITED: true (schnelle Ausführung)
     *        - SYNC_EXTRAS_MANUAL: true (Benutzer-ähnliche Priorität)
     *
     *        **Use Cases:**
     *        - Instant Sync nach Change-Trigger (TriggerContentUri)
     *        - Geringe Latenz-Anforderungen
     *
     * @param account Kerio-Account
     * @return void
     */
    public static void requestExpeditedSync(Account account) {
        Bundle extras = new Bundle();
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);

        ContentResolver.requestSync(account, CALENDAR_AUTHORITY, extras);
        Log.i(TAG, "requestExpeditedSync(): expedited Sync angefordert für " + account.name);
    }

    /**
     * @brief Gibt Calendar Events-URI zurück
     *
     * @return CalendarContract.Events.CONTENT_URI
     */
    public static android.net.Uri getCalendarEventsUri() {
        return CalendarContract.Events.CONTENT_URI;
    }

    /**
     * @brief Protokolliert aktuellen Sync-Framework-Status (Best-Effort)
     *
     *        **Ausgabe:**
     *        - isSyncable: 0 = nicht syncbar, 1 = syncbar
     *        - syncAutomatically: true = automatischer Sync enabled
     *
     *        **Exception-Handling:**
     *        - SecurityException ohne WRITE_SYNC_SETTINGS wird ignoriert
     *
     * @param context Android Context
     * @param account Kerio-Account
     * @return void
     */
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
