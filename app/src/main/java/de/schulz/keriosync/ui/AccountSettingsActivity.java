package de.schulz.keriosync.ui;

import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import de.schulz.keriosync.R;
import de.schulz.keriosync.auth.KerioAccountConstants;

/**
 * Activity, in der der Benutzer die Kerio-Account-Daten eingibt
 * (Server-URL, Benutzername, Passwort).
 *
 * Diese Activity wird sowohl direkt (App-Icon) als auch
 * über den AccountAuthenticator aufgerufen.
 */
public class AccountSettingsActivity extends AppCompatActivity {

    // Optional: eigenes Extra, falls du die Activity auch manuell aus der App
    // mit einer Response starten möchtest. Wird im aktuellen Flow nicht benötigt,
    // schadet aber auch nicht.
    public static final String EXTRA_ACCOUNT_AUTHENTICATOR_RESPONSE =
            "de.schulz.keriosync.EXTRA_ACCOUNT_AUTHENTICATOR_RESPONSE";

    public static final String EXTRA_IS_NEW_ACCOUNT =
            "de.schulz.keriosync.EXTRA_IS_NEW_ACCOUNT";

    private static final String TAG = "KerioSync.AccountSettings";

    private EditText edtServerUrl;
    private EditText edtUsername;
    private EditText edtPassword;
    private Button btnSave;

    private AccountAuthenticatorResponse mAccountAuthenticatorResponse;
    private boolean mIsNewAccount = true;
    private Account mExistingAccount = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account_settings);

        edtServerUrl = findViewById(R.id.edtServerUrl);
        edtUsername = findViewById(R.id.edtUsername);
        edtPassword = findViewById(R.id.edtPassword);
        btnSave = findViewById(R.id.btnSave);

        Intent intent = getIntent();
        if (intent != null) {
            // AccountAuthenticatorResponse kann vom System kommen,
            // wenn die Activity über "Konto hinzufügen" gestartet wird.
            mAccountAuthenticatorResponse = intent.getParcelableExtra(
                    AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE
            );

            // Optionaler Fallback, falls du die Activity manuell mit deinem
            // eigenen Extra startest:
            if (mAccountAuthenticatorResponse == null) {
                mAccountAuthenticatorResponse = intent.getParcelableExtra(
                        EXTRA_ACCOUNT_AUTHENTICATOR_RESPONSE
                );
            }

            if (mAccountAuthenticatorResponse != null) {
                mAccountAuthenticatorResponse.onRequestContinued();
            }

            mIsNewAccount = intent.getBooleanExtra(EXTRA_IS_NEW_ACCOUNT, true);

            // Optional: vorhandenen Account zum Bearbeiten übergeben
            String existingAccountName = intent.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
            if (!TextUtils.isEmpty(existingAccountName)) {
                AccountManager am = AccountManager.get(this);
                Account[] kerioAccounts = am.getAccountsByType(KerioAccountConstants.ACCOUNT_TYPE);
                for (Account acc : kerioAccounts) {
                    if (existingAccountName.equals(acc.name)) {
                        mExistingAccount = acc;
                        break;
                    }
                }

                if (mExistingAccount != null) {
                    Log.d(TAG, "Bestehender Account zum Bearbeiten gefunden: " + mExistingAccount.name);

                    String serverUrl = am.getUserData(mExistingAccount, KerioAccountConstants.KEY_SERVER_URL);
                    String username = mExistingAccount.name; // Meist Benutzername

                    if (!TextUtils.isEmpty(serverUrl)) {
                        edtServerUrl.setText(serverUrl);
                    }
                    if (!TextUtils.isEmpty(username)) {
                        edtUsername.setText(username);
                    }
                    // Passwort kann (muss aber nicht) vorausgefüllt werden – je nach Sicherheitsanforderung
                }
            }
        }

        btnSave.setOnClickListener(v -> saveAccount());
    }

    /**
     * Hilfsfunktion: Gibt alle vorhandenen Kerio-Accounts ins Log aus.
     */
    private void logExistingKerioAccounts(AccountManager accountManager, String context) {
        Account[] accounts = accountManager.getAccountsByType(KerioAccountConstants.ACCOUNT_TYPE);
        Log.d(TAG, "===== Kerio-Accounts (" + context + ") =====");
        Log.d(TAG, "Anzahl Kerio-Accounts: " + accounts.length);
        for (Account a : accounts) {
            Log.d(TAG, " - Account: " + a.name + " (type=" + a.type + ")");
        }
        Log.d(TAG, "=====================================");
    }

    /**
     * Liest die Eingabefelder aus, legt ggf. ein neues Konto an oder aktualisiert
     * ein bestehendes und setzt das Result für den AccountAuthenticator.
     */
    private void saveAccount() {
        String serverUrl = edtServerUrl.getText().toString().trim();
        String username = edtUsername.getText().toString().trim();
        String password = edtPassword.getText().toString(); // Passwort nicht trimmen

        if (TextUtils.isEmpty(serverUrl)) {
            edtServerUrl.setError("Bitte Server-URL eingeben");
            edtServerUrl.requestFocus();
            return;
        }

        if (TextUtils.isEmpty(username)) {
            edtUsername.setError("Bitte Benutzernamen eingeben");
            edtUsername.requestFocus();
            return;
        }

        if (TextUtils.isEmpty(password)) {
            edtPassword.setError("Bitte Passwort eingeben");
            edtPassword.requestFocus();
            return;
        }

        AccountManager accountManager = AccountManager.get(this);
        logExistingKerioAccounts(accountManager, "vor saveAccount()");

        Account account;
        if (mIsNewAccount) {
            account = new Account(username, KerioAccountConstants.ACCOUNT_TYPE);

            // User-Daten (z.B. Server-URL) speichern
            Bundle userData = new Bundle();
            userData.putString(KerioAccountConstants.KEY_SERVER_URL, serverUrl);

            boolean created = accountManager.addAccountExplicitly(account, password, userData);
            if (!created) {
                // Konto existiert sehr wahrscheinlich bereits
                Toast.makeText(
                        this,
                        "Konto konnte nicht angelegt werden (existiert evtl. schon).",
                        Toast.LENGTH_LONG
                ).show();

                logExistingKerioAccounts(accountManager, "nach fehlgeschlagenem addAccountExplicitly()");
                finish();
                return;
            }

            Log.d(TAG, "Neuer Kerio-Account angelegt: " + account.name);
        } else {
            // Bestehendes Konto aktualisieren
            if (mExistingAccount == null) {
                // Fallback, falls aus irgendeinem Grund kein bestehender Account referenziert wird
                account = new Account(username, KerioAccountConstants.ACCOUNT_TYPE);
            } else {
                account = mExistingAccount;
            }

            accountManager.setPassword(account, password);
            accountManager.setUserData(account, KerioAccountConstants.KEY_SERVER_URL, serverUrl);

            Log.d(TAG, "Bestehender Kerio-Account aktualisiert: " + account.name);
        }

        // WICHTIG: Keine WRITE_SYNC_SETTINGS-Methoden mehr verwenden
        // (ContentResolver.setIsSyncable / setSyncAutomatically), da diese
        // auf aktuellen Android-Versionen nur noch System-/Privileged-Apps erlaubt sind.

        logExistingKerioAccounts(accountManager, "nach saveAccount()");

        int countAfter = accountManager.getAccountsByType(KerioAccountConstants.ACCOUNT_TYPE).length;
        Toast.makeText(
                this,
                "Konto gespeichert. Kerio-Accounts im System: " + countAfter,
                Toast.LENGTH_LONG
        ).show();

        // Wenn die Activity vom AccountAuthenticator gestartet wurde, Ergebnis zurückgeben
        if (mAccountAuthenticatorResponse != null) {
            Bundle result = new Bundle();
            result.putString(AccountManager.KEY_ACCOUNT_NAME, account.name);
            result.putString(AccountManager.KEY_ACCOUNT_TYPE, account.type);
            mAccountAuthenticatorResponse.onResult(result);
        }

        setResult(RESULT_OK);
        finish();
    }
}
