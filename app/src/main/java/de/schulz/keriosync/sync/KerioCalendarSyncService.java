package de.schulz.keriosync.sync;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class KerioCalendarSyncService extends Service {

    private static KerioCalendarSyncAdapter sSyncAdapter = null;
    private static final Object sSyncAdapterLock = new Object();

    @Override
    public void onCreate() {
        super.onCreate();
        synchronized (sSyncAdapterLock) {
            if (sSyncAdapter == null) {
                sSyncAdapter = new KerioCalendarSyncAdapter(getApplicationContext(), true);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return sSyncAdapter.getSyncAdapterBinder();
    }
}
