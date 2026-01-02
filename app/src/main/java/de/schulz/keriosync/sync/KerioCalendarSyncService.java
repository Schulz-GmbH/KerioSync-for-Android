/**
 * @file KerioCalendarSyncService.java
 * @brief Service zum Bereitstellen des Kalender-SyncAdapter für Android
 *
 * @author Simon Marcel Linden
 * @date 2026
 * @version 0.9.8
 */
package de.schulz.keriosync.sync;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * @class KerioCalendarSyncService
 * @brief Service-Wrapper für KerioCalendarSyncAdapter.
 *        Android bindet an diesen Service, wenn ein Sync durchgeführt werden
 *        soll.
 *        Der Service verwaltet die SyncAdapter-Instanz (Singleton) und handhabt
 *        die initiale Account-Migration beim Service-Start.
 */
public class KerioCalendarSyncService extends Service {

    private static final String TAG = "KerioCalendarSyncService";

    /** Singleton-Instanz des SyncAdapter */
    private static KerioCalendarSyncAdapter sSyncAdapter = null;
    /** Lock für Thread-sichere Initialisierung */
    private static final Object sSyncAdapterLock = new Object();

    /** Flag zur Sicherstellung, dass Migration nur 1x pro Prozess läuft */
    private static volatile boolean sMigratedOnceInProcess = false;

    /**
     * @brief Service-Startup: Initialisiert SyncAdapter und führt ggf.
     *        Konten-Migration durch.
     *        Die Konten-Migration wird nur beim ersten onCreate() in diesem Prozess
     *        ausgeführt.
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

        synchronized (sSyncAdapterLock) {
            if (sSyncAdapter == null) {
                Log.i(TAG, "Erzeuge neue KerioCalendarSyncAdapter-Instanz.");
                /**
                 * @brief Liefert das SyncAdapter-Binder-Interface beim Bind-Aufruf.
                 * @param intent Bind-Intent
                 * @return SyncAdapter-Binder
                 */
                sSyncAdapter = new KerioCalendarSyncAdapter(getApplicationContext(), true);
            } else {
                Log.i(TAG, "Verwende bestehende KerioCalendarSyncAdapter-Instanz.");
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG,
                "onBind() aufgerufen. Intent=" + intent + ", action=" + (intent != null ? intent.getAction() : "null"));
        return sSyncAdapter.getSyncAdapterBinder();
    }
}
