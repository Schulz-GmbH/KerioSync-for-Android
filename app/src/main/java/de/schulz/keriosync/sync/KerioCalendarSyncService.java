package de.schulz.keriosync.sync;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * Service, der den KerioCalendarSyncAdapter dem System zur Verfügung stellt.
 */
public class KerioCalendarSyncService extends Service {

    private static final String TAG = "KerioCalendarSyncService";

    private static KerioCalendarSyncAdapter sSyncAdapter = null;
    private static final Object sSyncAdapterLock = new Object();

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate() aufgerufen – Service wird erstellt.");

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
        Log.i(TAG, "onBind() aufgerufen. Intent=" + intent + ", action=" + intent.getAction());
        return sSyncAdapter.getSyncAdapterBinder();
    }
}
