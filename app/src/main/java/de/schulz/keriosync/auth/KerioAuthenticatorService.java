package de.schulz.keriosync.auth;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * Service, der den KerioAccountAuthenticator dem System zur Verfügung stellt.
 */
public class KerioAuthenticatorService extends Service {

    private KerioAccountAuthenticator mAuthenticator;

    @Override
    public void onCreate() {
        super.onCreate();
        mAuthenticator = new KerioAccountAuthenticator(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        // Dem System den IBinder des Authenticators liefern
        return mAuthenticator.getIBinder();
    }
}
