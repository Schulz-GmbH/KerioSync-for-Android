package de.schulz.keriosync.ui;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import de.schulz.keriosync.R;
import de.schulz.keriosync.auth.KerioAccountConstants;

/**
 * Activity, in der der Benutzer die Kerio-Account-Daten eingibt
 * (Server-URL, Benutzername, Passwort, SSL-Optionen, Permissions).
 *
 * Diese Activity wird sowohl direkt (App-Icon) als auch
 * über den AccountAuthenticator aufgerufen.
 */
public class AccountSettingsActivity extends AppCompatActivity {

    public static final String EXTRA_ACCOUNT_AUTHENTICATOR_RESPONSE =
            "de.schulz.keriosync.EXTRA_ACCOUNT_AUTHENTICATOR_RESPONSE";

    public static final String EXTRA_IS_NEW_ACCOUNT =
            "de.schulz.keriosync.EXTRA_IS_NEW_ACCOUNT";

    private static final String TAG = "KerioSync.AccountSettings";

    private static final int REQ_PICK_CA_CERT = 1001;
    private static final int REQ_PERMISSIONS_CALENDAR = 2001;

    private EditText edtServerUrl;
    private EditText edtUsername;
    private EditText edtPassword;
    private CheckBox chkTrustAllCerts;
    private TextView txtCustomCaInfo;
    private Button btnPickCustomCa;
    private Button btnClearCustomCa;
    private Button btnSave;

    private AccountAuthenticatorResponse mAccountAuthenticatorResponse;
    private boolean mIsNewAccount = true;
    private Account mExistingAccount = null;

    private Uri mSelectedCaUri = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account_settings);

        edtServerUrl = findViewById(R.id.edtServerUrl);
        edtUsername = findViewById(R.id.edtUsername);
        edtPassword = findViewById(R.id.edtPassword);
        chkTrustAllCerts = findViewById(R.id.chkTrustAllCerts);
        txtCustomCaInfo = findViewById(R.id.txtCustomCaInfo);
        btnPickCustomCa = findViewById(R.id.btnPickCustomCa);
        btnClearCustomCa = findViewById(R.id.btnClearCustomCa);
        btnSave = findViewById(R.id.btnSave);

        // *** WICHTIG: Runtime-Permissions sicherstellen ***
        ensureRequiredPermissions();

        Intent intent = getIntent();
        if (intent != null) {
            mAccountAuthenticatorResponse = intent.getParcelableExtra(
                    AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE
            );

            if (mAccountAuthenticatorResponse == null) {
                mAccountAuthenticatorResponse = intent.getParcelableExtra(
                        EXTRA_ACCOUNT_AUTHENTICATOR_RESPONSE
                );
            }

            if (mAccountAuthenticatorResponse != null) {
                mAccountAuthenticatorResponse.onRequestContinued();
            }

            mIsNewAccount = intent.getBooleanExtra(EXTRA_IS_NEW_ACCOUNT, true);

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

                    AccountManager am2 = AccountManager.get(this);
                    String serverUrl = am2.getUserData(mExistingAccount, KerioAccountConstants.KEY_SERVER_URL);
                    String username = mExistingAccount.name;
                    String trustAllStr = am2.getUserData(mExistingAccount, KerioAccountConstants.KEY_SSL_TRUST_ALL);
                    String caUriStr = am2.getUserData(mExistingAccount, KerioAccountConstants.KEY_SSL_CUSTOM_CA_URI);

                    if (!TextUtils.isEmpty(serverUrl)) {
                        edtServerUrl.setText(serverUrl);
                    }
                    if (!TextUtils.isEmpty(username)) {
                        edtUsername.setText(username);
                    }

                    boolean trustAll = "1".equals(trustAllStr);
                    chkTrustAllCerts.setChecked(trustAll);

                    if (!TextUtils.isEmpty(caUriStr)) {
                        mSelectedCaUri = Uri.parse(caUriStr);
                    }
                }
            }
        }

        updateCaInfoText(mSelectedCaUri);
        updateCaControlsEnabled();

        chkTrustAllCerts.setOnCheckedChangeListener((buttonView, isChecked) -> updateCaControlsEnabled());

        btnPickCustomCa.setOnClickListener(v -> openCaPicker());
        btnClearCustomCa.setOnClickListener(v -> {
            mSelectedCaUri = null;
            updateCaInfoText(null);
        });

        btnSave.setOnClickListener(v -> saveAccount());
    }

    // ------------------------------------------------------------------------
    // Runtime-Permissions
    // ------------------------------------------------------------------------

    /**
     * Stellt sicher, dass die App die benötigten Berechtigungen
     * READ_CALENDAR, WRITE_CALENDAR und GET_ACCOUNTS zur Laufzeit hat.
     *
     * Ab Android 6+ müssen diese explizit vom Nutzer bestätigt werden.
     */
    private void ensureRequiredPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Unter Android < 6 sind zur Laufzeit keine Permissions nötig.
            return;
        }

        boolean hasReadCalendar = ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
        boolean hasWriteCalendar = ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED;
        boolean hasGetAccounts = ContextCompat.checkSelfPermission(
                this, Manifest.permission.GET_ACCOUNTS) == PackageManager.PERMISSION_GRANTED;

        if (hasReadCalendar && hasWriteCalendar && hasGetAccounts) {
            Log.d(TAG, "Alle erforderlichen Berechtigungen bereits vorhanden.");
            return;
        }

        // Berechtigungen anfragen
        Log.d(TAG, "Fordere erforderliche Berechtigungen an (READ/WRITE_CALENDAR, GET_ACCOUNTS).");
        ActivityCompat.requestPermissions(
                this,
                new String[]{
                        Manifest.permission.READ_CALENDAR,
                        Manifest.permission.WRITE_CALENDAR,
                        Manifest.permission.GET_ACCOUNTS
                },
                REQ_PERMISSIONS_CALENDAR
        );
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_PERMISSIONS_CALENDAR) {
            boolean allGranted = true;
            if (grantResults.length == 0) {
                allGranted = false;
            } else {
                for (int result : grantResults) {
                    if (result != PackageManager.PERMISSION_GRANTED) {
                        allGranted = false;
                        break;
                    }
                }
            }

            if (allGranted) {
                Log.d(TAG, "Alle angeforderten Berechtigungen wurden gewährt.");
                Toast.makeText(
                        this,
                        "Kalender- und Konto-Berechtigungen wurden erteilt. Sync kann jetzt korrekt laufen.",
                        Toast.LENGTH_LONG
                ).show();
            } else {
                Log.w(TAG, "Mindestens eine benötigte Berechtigung wurde verweigert.");
                Toast.makeText(
                        this,
                        "Ohne Kalender-Berechtigungen kann KerioSync keine Termine synchronisieren.",
                        Toast.LENGTH_LONG
                ).show();
            }
        }
    }

    // ------------------------------------------------------------------------
    // SSL / Zertifikats-UI
    // ------------------------------------------------------------------------

    private void updateCaControlsEnabled() {
        boolean trustAll = chkTrustAllCerts.isChecked();
        // Wenn „trust all“ aktiv ist, Custom-CA-Steuerung deaktivieren
        btnPickCustomCa.setEnabled(!trustAll);
        btnClearCustomCa.setEnabled(!trustAll);
    }

    private void updateCaInfoText(@Nullable Uri uri) {
        if (uri == null) {
            txtCustomCaInfo.setText("Kein eigenes Zertifikat ausgewählt. Es wird der System-Zertifikatsspeicher verwendet.");
        } else {
            txtCustomCaInfo.setText("Eigenes Zertifikat ausgewählt:\n" + uri.toString());
        }
    }

    private void openCaPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Zertifikate können verschieden MIME-Typen haben – wir erlauben mehrere
        intent.setType("*/*");
        String[] mimeTypes = new String[]{
                "application/x-x509-ca-cert",
                "application/x-x509-user-cert",
                "application/pkix-cert",
                "application/octet-stream"
        };
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        startActivityForResult(intent, REQ_PICK_CA_CERT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_PICK_CA_CERT && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                // Persistente Leseberechtigung sichern
                try {
                    final int takeFlags = data.getFlags()
                            & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    getContentResolver().takePersistableUriPermission(uri, takeFlags);
                } catch (Exception e) {
                    Log.w(TAG, "Konnte persistableUriPermission nicht setzen: " + e.getMessage());
                }

                mSelectedCaUri = uri;
                updateCaInfoText(uri);
            }
        }
    }

    // ------------------------------------------------------------------------
    // Account-Speicherung
    // ------------------------------------------------------------------------

    private void logExistingKerioAccounts(AccountManager accountManager, String context) {
        Account[] accounts = accountManager.getAccountsByType(KerioAccountConstants.ACCOUNT_TYPE);
        Log.d(TAG, "===== Kerio-Accounts (" + context + ") =====");
        Log.d(TAG, "Anzahl Kerio-Accounts: " + accounts.length);
        for (Account a : accounts) {
            Log.d(TAG, " - Account: " + a.name + " (type=" + a.type + ")");
        }
        Log.d(TAG, "=====================================");
    }

    private void saveAccount() {
        String serverUrl = edtServerUrl.getText().toString().trim();
        String username = edtUsername.getText().toString().trim();
        String password = edtPassword.getText().toString(); // Passwort nicht trimmen
        boolean trustAll = chkTrustAllCerts.isChecked();
        String trustAllStr = trustAll ? "1" : "0";
        String caUriStr = (mSelectedCaUri != null) ? mSelectedCaUri.toString() : null;

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

            Bundle userData = new Bundle();
            userData.putString(KerioAccountConstants.KEY_SERVER_URL, serverUrl);
            userData.putString(KerioAccountConstants.KEY_SSL_TRUST_ALL, trustAllStr);
            if (caUriStr != null) {
                userData.putString(KerioAccountConstants.KEY_SSL_CUSTOM_CA_URI, caUriStr);
            }

            boolean created = accountManager.addAccountExplicitly(account, password, userData);
            if (!created) {
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
            if (mExistingAccount == null) {
                account = new Account(username, KerioAccountConstants.ACCOUNT_TYPE);
            } else {
                account = mExistingAccount;
            }

            accountManager.setPassword(account, password);
            accountManager.setUserData(account, KerioAccountConstants.KEY_SERVER_URL, serverUrl);
            accountManager.setUserData(account, KerioAccountConstants.KEY_SSL_TRUST_ALL, trustAllStr);
            if (caUriStr != null) {
                accountManager.setUserData(account, KerioAccountConstants.KEY_SSL_CUSTOM_CA_URI, caUriStr);
            } else {
                accountManager.setUserData(account, KerioAccountConstants.KEY_SSL_CUSTOM_CA_URI, null);
            }

            Log.d(TAG, "Bestehender Kerio-Account aktualisiert: " + account.name);
        }

        logExistingKerioAccounts(accountManager, "nach saveAccount()");

        int countAfter = accountManager.getAccountsByType(KerioAccountConstants.ACCOUNT_TYPE).length;
        Toast.makeText(
                this,
                "Konto gespeichert. Kerio-Accounts im System: " + countAfter,
                Toast.LENGTH_LONG
        ).show();

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
