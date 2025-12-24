package de.schulz.keriosync.sync;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * Service, der den Kontakte-SyncAdapter bereitstellt.
 * Android bindet hieran, wenn ein Contacts-Sync durchgeführt werden soll.
 */
public class KerioContactsSyncService extends Service {

    private static final String TAG = "KerioContactsSyncService";

    private static KerioContactsSyncAdapter sSyncAdapter;

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

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "onBind() aufgerufen. Intent=" + intent + ", action=" + intent.getAction());
        return sSyncAdapter.getSyncAdapterBinder();
    }
}
