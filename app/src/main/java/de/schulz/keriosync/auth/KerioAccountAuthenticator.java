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
     * Optionaler Extra-Key, falls du später z.B. eine Server-URL
     * o.Ä. direkt an die Activity durchreichen möchtest.
     */
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

        // Sicherheitscheck: Nur unseren eigenen Account-Typ akzeptieren
        if (!KerioAccountConstants.ACCOUNT_TYPE.equals(accountType)) {
            Bundle error = new Bundle();
            error.putString(AccountManager.KEY_ERROR_MESSAGE,
                    "Unsupported account type: " + accountType);
            return error;
        }

        // Wird vom System aufgerufen, wenn du in den Einstellungen
        // „Konto hinzufügen → Kerio Sync“ auswählst.
        Intent intent = new Intent(mContext, AccountSettingsActivity.class);
        intent.putExtra(AccountSettingsActivity.EXTRA_ACCOUNT_AUTHENTICATOR_RESPONSE, response);
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

        // Kerio verwendet (in unserer aktuellen Planung) Basic Auth.
        // Für den Android-AccountManager reicht hier ein Dummy-Token
        // bzw. wir geben nur Account-Daten zurück.
        Bundle result = new Bundle();
        result.putString(AccountManager.KEY_ACCOUNT_NAME, account.name);
        result.putString(AccountManager.KEY_ACCOUNT_TYPE, account.type);
        // Optional: AccountManager.KEY_AUTHTOKEN setzen,
        // falls du später echte Tokens verwendest.
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
        intent.putExtra(AccountSettingsActivity.EXTRA_ACCOUNT_AUTHENTICATOR_RESPONSE, response);
        intent.putExtra(AccountSettingsActivity.EXTRA_IS_NEW_ACCOUNT, false);
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
