/**
 * @file KerioAuthenticatorService.java
 * @brief Android-Service für die Bereitstellung des Account-Authenticators
 * 
 * @author Simon Marcel Linden
 * @date 2026
 * @version 0.9.8
 */
package de.schulz.keriosync.auth;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * @class KerioAuthenticatorService
 * @brief Service zur Bereitstellung des KerioAccountAuthenticators
 *
 * Dieser Android-Service stellt den KerioAccountAuthenticator dem Android-System
 * zur Verfügung. Der Service wird vom System automatisch gestartet, wenn der
 * Account-Authenticator benötigt wird (z.B. beim Hinzufügen oder Verwalten von
 * Kerio-Konten in den Systemeinstellungen).
 * 
 * Der Service muss in der AndroidManifest.xml mit den entsprechenden Intent-Filtern
 * für android.accounts.AccountAuthenticator registriert sein.
 * 
 * @extends android.app.Service
 * @see KerioAccountAuthenticator
 * @see android.accounts.AbstractAccountAuthenticator
 */
public class KerioAuthenticatorService extends Service {

    /**
     * @brief Instanz des Account-Authenticators
     * 
     * Diese Instanz wird in onCreate() erstellt und über onBind() dem System
     * zur Verfügung gestellt.
     */
    private KerioAccountAuthenticator mAuthenticator;

    /**
     * @brief Initialisiert den Service
     * 
     * Erstellt eine neue Instanz des KerioAccountAuthenticator, die für die
     * Dauer der Service-Lebensdauer verwendet wird.
     * 
     * @see KerioAccountAuthenticator#KerioAccountAuthenticator(Context)
     */
    @Override
    public void onCreate() {
        super.onCreate();
        mAuthenticator = new KerioAccountAuthenticator(this);
    }

    /**
     * @brief Bindet den Service und liefert den Authenticator-IBinder
     * 
     * Diese Methode wird vom Android-System aufgerufen, wenn eine Verbindung
     * zum Service hergestellt werden soll. Sie gibt den IBinder des
     * Authenticators zurück, über den das System mit dem Authenticator
     * kommunizieren kann.
     * 
     * @param intent Der Intent, der zum Binden des Services verwendet wurde
     * @return IBinder-Interface des KerioAccountAuthenticators
     * @see android.accounts.AbstractAccountAuthenticator#getIBinder()
     */
    @Override
    public IBinder onBind(Intent intent) {
        // Dem System den IBinder des Authenticators liefern
        return mAuthenticator.getIBinder();
    }
}
