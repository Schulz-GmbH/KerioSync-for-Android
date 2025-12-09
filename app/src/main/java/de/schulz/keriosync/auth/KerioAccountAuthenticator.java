package de.schulz.keriosync.auth;

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.accounts.NetworkErrorException;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import de.schulz.keriosync.ui.AccountSettingsActivity;

/**
 * Account-Authenticator für Kerio-Konten.
 *
 * Diese Klasse integriert unseren eigenen Account-Typ in das Android-Account-System.
 * Sie wird über den KerioAuthenticatorService bereitgestellt.
 */
public class KerioAccountAuthenticator extends AbstractAccountAuthenticator {

    private final Context mContext;

    /**
     * Account-Typ für Kerio Sync.
     *
     * WICHTIG:
     * Dieser Wert muss mit android:accountType in res/xml/authenticator.xml
     * und res/xml/sync_calendars.xml übereinstimmen.
     *
     * Zur Vermeidung von Tippfehlern verweisen wir direkt auf KerioAccountConstants.
     */
    public static final String ACCOUNT_TYPE = KerioAccountConstants.ACCOUNT_TYPE;

    public static final String EXTRA_SERVER_URL = "EXTRA_SERVER_URL";

    public KerioAccountAuthenticator(Context context) {
        super(context);
        this.mContext = context;
    }

    @Override
    public Bundle editProperties(AccountAuthenticatorResponse response, String accountType) {
        // Keine globalen Properties nötig
        throw new UnsupportedOperationException();
    }

    @Override
    public Bundle addAccount(AccountAuthenticatorResponse response,
                             String accountType,
                             String authTokenType,
                             String[] requiredFeatures,
                             Bundle options) throws NetworkErrorException {

        // Wird vom System aufgerufen, wenn du in den Einstellungen
        // „Konto hinzufügen → Kerio Sync“ auswählst.

        Intent intent = new Intent(mContext, AccountSettingsActivity.class);

        // WICHTIG:
        // Die AccountAuthenticatorResponse muss mit dem offiziellen Key
        // in das Intent gelegt werden, damit die Activity sie über
        // AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE auslesen kann.
        intent.putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response);

        // Kennzeichnen, dass es sich um einen neuen Account handelt
        intent.putExtra(AccountSettingsActivity.EXTRA_IS_NEW_ACCOUNT, true);

        Bundle bundle = new Bundle();
        bundle.putParcelable(AccountManager.KEY_INTENT, intent);
        return bundle;
    }

    @Override
    public Bundle confirmCredentials(AccountAuthenticatorResponse response,
                                     Account account,
                                     Bundle options) throws NetworkErrorException {
        // Optional: Credentials erneut prüfen
        return null;
    }

    @Override
    public Bundle getAuthToken(AccountAuthenticatorResponse response,
                               Account account,
                               String authTokenType,
                               Bundle options) throws NetworkErrorException {
        // Kerio verwendet Basic Auth, hier reicht ein Dummy.
        Bundle result = new Bundle();
        result.putString(AccountManager.KEY_ACCOUNT_NAME, account.name);
        result.putString(AccountManager.KEY_ACCOUNT_TYPE, account.type);
        // Optional: AccountManager.KEY_AUTHTOKEN setzen, falls du später Tokens verwendest.
        return result;
    }

    @Override
    public String getAuthTokenLabel(String authTokenType) {
        return "Kerio Auth Token";
    }

    @Override
    public Bundle updateCredentials(AccountAuthenticatorResponse response,
                                    Account account,
                                    String authTokenType,
                                    Bundle options) throws NetworkErrorException {

        // Konto-Daten aktualisieren – wir nutzen dieselbe Activity,
        // diesmal im „Update“-Modus.

        Intent intent = new Intent(mContext, AccountSettingsActivity.class);
        intent.putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response);
        intent.putExtra(AccountSettingsActivity.EXTRA_IS_NEW_ACCOUNT, false);

        // Optional: bestehenden Account-Namen übergeben, falls du in der
        // Activity dessen Daten vorausfüllen möchtest.
        intent.putExtra(AccountManager.KEY_ACCOUNT_NAME, account.name);

        Bundle bundle = new Bundle();
        bundle.putParcelable(AccountManager.KEY_INTENT, intent);
        return bundle;
    }

    @Override
    public Bundle hasFeatures(AccountAuthenticatorResponse response,
                              Account account,
                              String[] features) throws NetworkErrorException {
        // Aktuell keine speziellen Features
        Bundle bundle = new Bundle();
        bundle.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false);
        return bundle;
    }
}
