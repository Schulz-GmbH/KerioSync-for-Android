/**
 * @file    KerioContactsSyncService.java
 * @brief   Service-Wrapper für KerioContactsSyncAdapter zur Integration mit Android Sync Framework
 * @author  Kerio Sync Team
 * @date    2025
 * @version 1.0
 */

package de.schulz.keriosync.sync;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * @class KerioContactsSyncService
 * @brief Service, der den Kontakte-SyncAdapter für das Android Sync Framework
 *        bereitstellt
 *
 *        **Funktion:**
 *        - Android Framework bindet an diesen Service, wenn ein Contacts-Sync
 *        durchgeführt werden soll
 *        - Verwaltet Singleton-Instanz des KerioContactsSyncAdapter
 *        - Triggert Kontakt-Account-Migration beim ersten Service-Start pro
 *        Prozess
 *
 *        **Lifecycle:**
 *        - onCreate(): Singleton-Erstellung, einmalige Migration per Prozess
 *        - onBind(): Rückgabe des SyncAdapter-Binders für Framework-Integration
 *
 *        **Wichtig:**
 *        - Im Manifest mit android.permission.BIND_SYNC_ADAPTER registriert
 *        - In res/xml/sync_contacts.xml konfiguriert
 */
public class KerioContactsSyncService extends Service {

    private static final String TAG = "KerioContactsSyncService";

    private static KerioContactsSyncAdapter sSyncAdapter;

    // verhindert, dass wir bei jedem Service-Start alles neu “anwerfen”
    private static volatile boolean sMigratedOnceInProcess = false;

    /**
     * @brief Initialisiert Service: Singleton-SyncAdapter und Account-Migration
     *        **Ablauf:**
     *        1. Prüft sMigratedOnceInProcess Flag (pro Prozess einmalig)
     *        2. Falls erste Ausführung im Prozess: Führt
     *        KerioAccountMigrator.migrateAllAccounts() durch
     *        3. Erstellt/cached KerioContactsSyncAdapter als Thread-sicheres
     *        Singleton
     *        4. Protokolliert Aktionen
     *
     *        **Migration:**
     *        - Wird nur 1x pro Prozesslauf ausgeführt (verhindert redundante
     *        Reschedules)
     *        - Füllt fehlende Account-Settings mit Defaults
     *
     * @return void
     */
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate() aufgerufen – Service wird erstellt.");

        // Migration/Reschedule nur 1x pro Prozesslauf
        if (!sMigratedOnceInProcess) {
            sMigratedOnceInProcess = true;
            KerioAccountMigrator.migrateAllAccounts(this);
        } else {
            Log.i(TAG, "onCreate(): Migration übersprungen (bereits in diesem Prozess ausgeführt).");
        }

        if (sSyncAdapter == null) {
            synchronized (KerioContactsSyncService.class) {
                if (sSyncAdapter == null) {
                    sSyncAdapter = new KerioContactsSyncAdapter(getApplicationContext(), true);
                    Log.i(TAG, "Neuen KerioContactsSyncAdapter erstellt.");
                }
            }
        } else {
            Log.i(TAG, "Verwende bestehende KerioContactsSyncAdapter-Instanz.");
        }
    }

    /**
     * @brief Liefert den SyncAdapter-Binder für Android Sync Framework
     *        **Funktion:**
     *        - Android Framework ruft diese Methode auf, um den SyncAdapter zu
     *        binden
     *        - Gibt den IBinder des Singleton-SyncAdapters zurück
     *        - Verbindung wird für Sync-Operationen vom Framework genutzt
     *
     * @param intent Intent vom Framework (action="android.content.SyncAdapter")
     * @return IBinder des SyncAdapter, für Framework-Kommunikation
     */
    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "onBind() aufgerufen. Intent=" + intent + ", action=" + intent.getAction());
        return sSyncAdapter.getSyncAdapterBinder();
    }
}
