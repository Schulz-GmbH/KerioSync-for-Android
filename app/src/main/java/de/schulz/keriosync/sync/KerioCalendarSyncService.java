// ===== app/src/main/java/de/schulz/keriosync/sync/KerioCalendarSyncService.java =====
package de.schulz.keriosync.sync;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * Service, der den SyncAdapter bereitstellt.
 * Android bindet hieran, wenn ein Sync durchgeführt werden soll.
 */
public class KerioCalendarSyncService extends Service {

    private static final String TAG = "KerioCalendarSyncService";

    private static KerioCalendarSyncAdapter sSyncAdapter = null;
    private static final Object sSyncAdapterLock = new Object();

    // verhindert, dass wir bei jedem Service-Start alles neu “anwerfen”
    private static volatile boolean sMigratedOnceInProcess = false;

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
                sSyncAdapter = new KerioCalendarSyncAdapter(getApplicationContext(), true);
            } else {
                Log.i(TAG, "Verwende bestehende KerioCalendarSyncAdapter-Instanz.");
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "onBind() aufgerufen. Intent=" + intent + ", action=" + (intent != null ? intent.getAction() : "null"));
        return sSyncAdapter.getSyncAdapterBinder();
    }
}
