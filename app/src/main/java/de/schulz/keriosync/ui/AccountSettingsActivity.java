/**
 * @file AccountSettingsActivity.java
 * @brief UI-Activity für Kerio-Kontoeinstellungen und Synchronisierungskonfiguration
 * @author Simon Schulz
 * @date 2. Januar 2026
 * @version 1.0
 *
 * Diese Activity verwaltet die Benutzeroberfläche für:
 * - Kontoerstellung und -verwaltung (Server-URL, Benutzername, Passwort)
 * - SSL/TLS-Zertifikatsoptionen (Standard-CA oder benutzerdefinierte CA)
 * - Synchronisierungseinstellungen (Periodische und Sofort-Sync mit Intervallen)
 * - Runtime-Permissions für Kalender, Kontakte und Account-Manager
 *
 * Die Activity wird über zwei Wege aufgerufen:
 * 1. Direkt vom App-Icon (Kontoeinstellungen öffnen)
 * 2. Vom KerioAccountAuthenticator (Neue Kontos während Authentifizierung)
 *
 * Workflow:
 * - onCreate(): UI-Initialisierung, Account-Daten laden, Permissions prüfen
 * - saveAccount(): Validierung und Speicherung der Kontodaten in AccountManager
 * - KerioSyncScheduler.applyAll(): Aktiviert Periodic/Instant Sync basierend auf UI-Einstellungen
 * - Runtime-Permission-Handling: Veranlasst den Sync erst nach Permission-Gewährung
 */

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

import de.schulz.keriosync.BuildConfig;
import de.schulz.keriosync.R;
import de.schulz.keriosync.auth.KerioAccountConstants;
import de.schulz.keriosync.sync.KerioSyncScheduler;

/**
 * @class AccountSettingsActivity
 * @brief UI-Activity für Kerio-Kontoeinstellungen und
 *        Synchronisierungskonfiguration
 *
 *        Diese Activity verwaltet die Benutzeroberfläche für:
 *        - Kontoerstellung und -verwaltung (Server-URL, Benutzername, Passwort)
 *        - SSL/TLS-Zertifikatsoptionen (Standard-CA oder benutzerdefinierte CA)
 *        - Synchronisierungseinstellungen (Periodische und Sofort-Sync mit
 *        Intervallen)
 *        - Runtime-Permissions für Kalender, Kontakte und Account-Manager
 *
 *        Die Activity wird über zwei Wege aufgerufen:
 *        1. Direkt vom App-Icon (Kontoeinstellungen öffnen)
 *        2. Vom KerioAccountAuthenticator (Neue Kontos während
 *        Authentifizierung)
 *
 *        Workflow:
 *        - onCreate(): UI-Initialisierung, Account-Daten laden, Permissions
 *        prüfen
 *        - saveAccount(): Validierung und Speicherung der Kontodaten in
 *        AccountManager
 *        - KerioSyncScheduler.applyAll(): Aktiviert Periodic/Instant Sync
 *        basierend auf UI-Einstellungen
 *        - Runtime-Permission-Handling: Veranlasst den Sync erst nach
 *        Permission-Gewährung
 */
public class AccountSettingsActivity extends AppCompatActivity {

    public static final String EXTRA_ACCOUNT_AUTHENTICATOR_RESPONSE = "de.schulz.keriosync.EXTRA_ACCOUNT_AUTHENTICATOR_RESPONSE";

    public static final String EXTRA_IS_NEW_ACCOUNT = "de.schulz.keriosync.EXTRA_IS_NEW_ACCOUNT";

    private static final String TAG = "KerioSync.AccountSettings";

    private static final int REQ_PICK_CA_CERT = 1001;
    private static final int REQ_PERMISSIONS_ALL = 2001;

    private EditText edtServerUrl;
    private EditText edtUsername;
    private EditText edtPassword;
    private CheckBox chkTrustAllCerts;
    private TextView txtCustomCaInfo;
    private Button btnPickCustomCa;
    private Button btnClearCustomCa;
    private Button btnSave;

    /**
     * Wird gesetzt, wenn saveAccount() bereits gespeichert hat, aber noch
     * Permissions fehlen.
     */
    @Nullable
    private Account mPendingApplySchedulerAccount = null;

    // Sync Settings (neu)
    private CheckBox chkPeriodicSyncEnabled;
    private EditText edtPeriodicMinutes;
    private CheckBox chkInstantSyncEnabled;
    private EditText edtInstantUpdateDelaySeconds;
    private EditText edtInstantMaxDelaySeconds;

    private AccountAuthenticatorResponse mAccountAuthenticatorResponse;
    private boolean mIsNewAccount = true;
    private Account mExistingAccount = null;

    private Uri mSelectedCaUri = null;

    /**
     * @brief Initialisiert die Activity mit UI-Komponenten, Account-Daten und
     *        Permissions
     *        Dieser Lifecycle-Callback führt folgende Schritte durch:
     *        1. Bindet alle UI-Komponenten (EditText, CheckBox, Button)
     *        2. Prüft, ob ein bestehendes Konto bearbeitet wird, oder ein neues
     *        erstellt wird
     *        3. Lädt Account-Daten (Server-URL, Benutzername, SSL-Optionen) falls
     *        vorhanden
     *        4. Lädt Sync-Einstellungen (Periodisch/Sofort mit Intervallen) falls
     *        vorhanden
     *        5. Setzt Standard-Sync-Einstellungen für neue Kontos
     *        6. Sichert Runtime-Permissions (READ/WRITE_CALENDAR,
     *        READ/WRITE_CONTACTS, GET_ACCOUNTS)
     *        7. Registriert Click-Listener für CA-Zertifikat und
     *        Account-Speicherung
     *
     * @param savedInstanceState Bundle mit gespeicherten Activity-Zustand (kann
     *                           null sein)
     */
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

        chkPeriodicSyncEnabled = findViewById(R.id.chkPeriodicSyncEnabled);
        edtPeriodicMinutes = findViewById(R.id.edtPeriodicMinutes);
        chkInstantSyncEnabled = findViewById(R.id.chkInstantSyncEnabled);
        edtInstantUpdateDelaySeconds = findViewById(R.id.edtInstantUpdateDelaySeconds);
        edtInstantMaxDelaySeconds = findViewById(R.id.edtInstantMaxDelaySeconds);

        boolean hasExistingAccount = checkIfAccountExists();

        if (BuildConfig.DEV_PRESET_VALUES && !hasExistingAccount) {
            if (!BuildConfig.DEV_SERVER_URL.isEmpty())
                edtServerUrl.setText(BuildConfig.DEV_SERVER_URL);
            if (!BuildConfig.DEV_USERNAME.isEmpty())
                edtUsername.setText(BuildConfig.DEV_USERNAME);
            if (!BuildConfig.DEV_PASSWORD.isEmpty())
                edtPassword.setText(BuildConfig.DEV_PASSWORD);
        }

        // *** WICHTIG: Runtime-Permissions sicherstellen ***
        ensureRequiredPermissions();

        Intent intent = getIntent();
        if (intent != null) {
            mAccountAuthenticatorResponse = intent.getParcelableExtra(
                    AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE);

            if (mAccountAuthenticatorResponse == null) {
                mAccountAuthenticatorResponse = intent.getParcelableExtra(
                        EXTRA_ACCOUNT_AUTHENTICATOR_RESPONSE);
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

                    // Sync Settings laden
                    loadSyncSettings(am2, mExistingAccount);
                } else {
                    // Default Sync Settings für neuen Account
                    setDefaultSyncSettings();
                }
            } else {
                // Default Sync Settings für neuen Account
                setDefaultSyncSettings();
            }
        } else {
            setDefaultSyncSettings();
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

    /**
     * @brief Setzt Standard-Synchronisierungseinstellungen für ein neues Konto
     *        Wird aufgerufen, wenn ein neues Konto angelegt wird. Die Werte stammen
     *        aus KerioSyncScheduler-Konstanten und repräsentieren folgende
     *        Standardwerte:
     *        - Periodischer Sync: Aktiviert, 15 Minuten Intervall
     *        - Sofort-Sync: Aktiviert, 5 Sekunden Verzögerung, 30 Sekunden Maximum
     */
    private void setDefaultSyncSettings() {
        chkPeriodicSyncEnabled.setChecked(KerioSyncScheduler.DEFAULT_PERIODIC_ENABLED);
        edtPeriodicMinutes.setText(String.valueOf(KerioSyncScheduler.DEFAULT_PERIODIC_MINUTES));

        chkInstantSyncEnabled.setChecked(KerioSyncScheduler.DEFAULT_INSTANT_ENABLED);
        edtInstantUpdateDelaySeconds.setText(String.valueOf(KerioSyncScheduler.DEFAULT_INSTANT_UPDATE_DELAY_SECONDS));
        edtInstantMaxDelaySeconds.setText(String.valueOf(KerioSyncScheduler.DEFAULT_INSTANT_MAX_DELAY_SECONDS));
    }

    /**
     * @brief Lädt gespeicherte Synchronisierungseinstellungen aus dem
     *        AccountManager
     *        Liest die folgenden UserData-Felder:
     *        - KEY_PERIODIC_SYNC_ENABLED: "1" oder "0" (aktiviert/deaktiviert)
     *        - KEY_SYNC_INTERVAL_MINUTES: Periodisches Sync-Intervall in Minuten
     *        - KEY_INSTANT_SYNC_ENABLED: "1" oder "0" (aktiviert/deaktiviert)
     *        - KEY_INSTANT_SYNC_UPDATE_DELAY_SECONDS: Verzögerung bis Sofort-Sync
     *        in Sekunden
     *        - KEY_INSTANT_SYNC_MAX_DELAY_SECONDS: Maximale Verzögerung für
     *        Sofort-Sync
     *
     *        Fehlende Werte werden mit Standardwerten aus KerioSyncScheduler
     *        gefüllt.
     *
     * @param am      AccountManager für UserData-Zugriff
     * @param account Android-Account-Objekt mit gespeicherten Einstellungen
     */
    private void loadSyncSettings(AccountManager am, Account account) {
        String periodicEnabled = am.getUserData(account, KerioAccountConstants.KEY_PERIODIC_SYNC_ENABLED);
        String intervalMin = am.getUserData(account, KerioAccountConstants.KEY_SYNC_INTERVAL_MINUTES);

        String instantEnabled = am.getUserData(account, KerioAccountConstants.KEY_INSTANT_SYNC_ENABLED);
        String updateDelay = am.getUserData(account, KerioAccountConstants.KEY_INSTANT_SYNC_UPDATE_DELAY_SECONDS);
        String maxDelay = am.getUserData(account, KerioAccountConstants.KEY_INSTANT_SYNC_MAX_DELAY_SECONDS);

        chkPeriodicSyncEnabled.setChecked(!"0".equals(periodicEnabled));
        edtPeriodicMinutes
                .setText(TextUtils.isEmpty(intervalMin) ? String.valueOf(KerioSyncScheduler.DEFAULT_PERIODIC_MINUTES)
                        : intervalMin);

        chkInstantSyncEnabled.setChecked(!"0".equals(instantEnabled));
        edtInstantUpdateDelaySeconds.setText(
                TextUtils.isEmpty(updateDelay) ? String.valueOf(KerioSyncScheduler.DEFAULT_INSTANT_UPDATE_DELAY_SECONDS)
                        : updateDelay);
        edtInstantMaxDelaySeconds.setText(
                TextUtils.isEmpty(maxDelay) ? String.valueOf(KerioSyncScheduler.DEFAULT_INSTANT_MAX_DELAY_SECONDS)
                        : maxDelay);
    }

    /**
     * @brief Parst einen String in Integer mit Fallback auf Defaultwert
     *
     * @param val String-Wert zum Parsen (kann null oder leer sein)
     * @param def Fallback-Wert bei Parsing-Fehler oder null-Input
     * @return Geparster Integer-Wert oder def bei Fehler
     */
    private int parseIntOrDefault(String val, int def) {
        try {
            if (val == null)
                return def;
            return Integer.parseInt(val.trim());
        } catch (Exception ignored) {
            return def;
        }
    }

    /**
     * @brief Öffnet einen Datei-Picker zum Auswählen eines CA-Zertifikats
     *        Stellt ACTION_OPEN_DOCUMENT-Intent bereit mit Unterstützung für:
     *        - application/x-x509-ca-cert
     *        - application/x-x509-user-cert
     *        - application/pkix-cert
     *        - application/octet-stream (generisches Fallback)
     *
     *        Geht davon aus, dass das System eine Datei-App zur Auswahl
     *        bereitstellt.
     *        Ergebnis wird in onActivityResult() mit requestCode REQ_PICK_CA_CERT
     *        verarbeitet.
     */
    private void openCaPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        String[] mimeTypes = new String[] {
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

    /**
     * @brief Verarbeitet das Ergebnis der CA-Zertifikat-Dateiauswahl
     *
     *        Wird aufgerufen, nachdem der Benutzer eine Datei im Datei-Picker
     *        gewählt hat.
     *        Wenn Dateiauswahl erfolgreich war (resultCode==RESULT_OK):
     *        1. Liest Uri aus Intent-Daten
     *        2. Versucht persistable URI-Permission zu setzen (für Zugriff über
     *        Neustarts)
     *        3. Speichert Uri in mSelectedCaUri
     *        4. Aktualisiert CA-Info-Anzeige in UI
     *
     * @param requestCode Request-Identifikator (wird mit REQ_PICK_CA_CERT
     *                    verglichen)
     * @param resultCode  Activity-Ergebnis-Code (RESULT_OK bei Erfolg)
     * @param data        Intent mit Datei-Uri im Intent.getData()
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_PICK_CA_CERT && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
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

    /**
     * @brief Protokolliert alle vorhandenen Kerio-Accounts für Debugging
     *
     *        Gibt eine formatierte Liste aller registrierten Kerio-Accounts an
     *        Logcat aus.
     *        Nützlich für Debugging von Account-Management-Problemen.
     *
     * @param accountManager AccountManager-Instanz für Account-Abfrage
     * @param context        Kontextstring für Log-Überschrift (z.B. "vor
     *                       saveAccount()")
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
     * @brief Validiert und speichert die Kerio-Kontodaten im AccountManager
     *        Dieser zentrale Callback führt folgende Schritte durch:
     *        1. Liest alle UI-Feldwerte aus (Server-URL, Benutzername, Passwort,
     *        SSL, Sync-Einstellungen)
     *        2. Validiert erforderliche Felder (Server-URL, Benutzername, Passwort)
     *        3. Validiert und korrigiert Sync-Einstellungen (Minimum 15 Minuten
     *        periodisch)
     *        4. Erstellt oder aktualisiert Account im AccountManager mit UserData
     *        5. Prüft Runtime-Permissions:
     *        - Falls erteilt: Ruft KerioSyncScheduler.applyAll() auf und beendet
     *        Activity
     *        - Falls fehlend: Setzt mPendingApplySchedulerAccount und fordert
     *        Permissions an
     *        6. Nach Permission-Grant: Completes save via completeSaveAndFinish()
     *
     *        Fehlerbehandlung:
     *        - Leere Felder: Zeigt Fehler im EditText und requestFocus()
     *        - Account-Duplikate: Zeigt Toast und beendet Activity
     *        - Permission-Fehler: Zeigt Toast und blockiert Sync-Aktivierung
     */
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

        // Sync Settings sammeln
        boolean periodicEnabled = chkPeriodicSyncEnabled.isChecked();
        int periodicMinutes = parseIntOrDefault(edtPeriodicMinutes.getText().toString(),
                KerioSyncScheduler.DEFAULT_PERIODIC_MINUTES);
        if (periodicMinutes < KerioSyncScheduler.MIN_PERIODIC_MINUTES) {
            periodicMinutes = KerioSyncScheduler.MIN_PERIODIC_MINUTES;
        }

        boolean instantEnabled = chkInstantSyncEnabled.isChecked();
        int instantUpdateDelaySec = parseIntOrDefault(edtInstantUpdateDelaySeconds.getText().toString(),
                KerioSyncScheduler.DEFAULT_INSTANT_UPDATE_DELAY_SECONDS);
        int instantMaxDelaySec = parseIntOrDefault(edtInstantMaxDelaySeconds.getText().toString(),
                KerioSyncScheduler.DEFAULT_INSTANT_MAX_DELAY_SECONDS);
        if (instantUpdateDelaySec < 1)
            instantUpdateDelaySec = 1;
        if (instantMaxDelaySec < instantUpdateDelaySec)
            instantMaxDelaySec = instantUpdateDelaySec;

        AccountManager accountManager = AccountManager.get(this);
        logExistingKerioAccounts(accountManager, "vor saveAccount()");

        Account account;
        if (mIsNewAccount) {
            account = new Account(username, KerioAccountConstants.ACCOUNT_TYPE);

            Bundle userData = new Bundle();
            userData.putString(KerioAccountConstants.KEY_SERVER_URL, serverUrl);
            userData.putString(KerioAccountConstants.KEY_USERNAME, username);
            userData.putString(KerioAccountConstants.KEY_SSL_TRUST_ALL, trustAllStr);
            if (caUriStr != null) {
                userData.putString(KerioAccountConstants.KEY_SSL_CUSTOM_CA_URI, caUriStr);
            }

            // Sync Settings speichern
            userData.putString(KerioAccountConstants.KEY_PERIODIC_SYNC_ENABLED, periodicEnabled ? "1" : "0");
            userData.putString(KerioAccountConstants.KEY_SYNC_INTERVAL_MINUTES, String.valueOf(periodicMinutes));
            userData.putString(KerioAccountConstants.KEY_INSTANT_SYNC_ENABLED, instantEnabled ? "1" : "0");
            userData.putString(KerioAccountConstants.KEY_INSTANT_SYNC_UPDATE_DELAY_SECONDS,
                    String.valueOf(instantUpdateDelaySec));
            userData.putString(KerioAccountConstants.KEY_INSTANT_SYNC_MAX_DELAY_SECONDS,
                    String.valueOf(instantMaxDelaySec));

            boolean created = accountManager.addAccountExplicitly(account, password, userData);
            if (!created) {
                Toast.makeText(
                        this,
                        "Konto konnte nicht angelegt werden (existiert evtl. schon).",
                        Toast.LENGTH_LONG).show();

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
            accountManager.setUserData(account, KerioAccountConstants.KEY_USERNAME, username);
            accountManager.setUserData(account, KerioAccountConstants.KEY_SSL_TRUST_ALL, trustAllStr);
            if (caUriStr != null) {
                accountManager.setUserData(account, KerioAccountConstants.KEY_SSL_CUSTOM_CA_URI, caUriStr);
            } else {
                accountManager.setUserData(account, KerioAccountConstants.KEY_SSL_CUSTOM_CA_URI, null);
            }

            // Sync Settings speichern
            accountManager.setUserData(account, KerioAccountConstants.KEY_PERIODIC_SYNC_ENABLED,
                    periodicEnabled ? "1" : "0");
            accountManager.setUserData(account, KerioAccountConstants.KEY_SYNC_INTERVAL_MINUTES,
                    String.valueOf(periodicMinutes));
            accountManager.setUserData(account, KerioAccountConstants.KEY_INSTANT_SYNC_ENABLED,
                    instantEnabled ? "1" : "0");
            accountManager.setUserData(account, KerioAccountConstants.KEY_INSTANT_SYNC_UPDATE_DELAY_SECONDS,
                    String.valueOf(instantUpdateDelaySec));
            accountManager.setUserData(account, KerioAccountConstants.KEY_INSTANT_SYNC_MAX_DELAY_SECONDS,
                    String.valueOf(instantMaxDelaySec));

            Log.d(TAG, "Bestehender Kerio-Account aktualisiert: " + account.name);
        }

        // Scheduler anwenden (DAS ist der entscheidende Teil)
        if (!hasAllRequiredPermissions()) {
            // Permissions fehlen noch -> erst anfordern, dann Scheduler nachziehen
            mPendingApplySchedulerAccount = account;
            ensureRequiredPermissions();

            Toast.makeText(
                    this,
                    "Bitte Kalender- und Kontakte-Berechtigungen erlauben, damit die Synchronisation starten kann.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        KerioSyncScheduler.applyAll(this, account);
        completeSaveAndFinish(accountManager, account);

    }

    /**
     * @brief Finalisiert Account-Speicherung und schließt Activity
     *        Nach erfolgreichem KerioSyncScheduler.applyAll():
     *        1. Protokolliert aktuelle Kerio-Accounts (Debugging)
     *        2. Zeigt Erfolgs-Toast mit Account-Anzahl
     *        3. Antwortet auf AccountAuthenticatorResponse (falls von
     *        Authentifizierung aufgerufen)
     *        4. Setzt RESULT_OK und beendet Activity
     *
     * @param accountManager AccountManager für Account-Abfrage und Logging
     * @param account        Das gespeicherte/aktualisierte Account-Objekt
     */
    private void completeSaveAndFinish(AccountManager accountManager, Account account) {
        logExistingKerioAccounts(accountManager, "nach saveAccount()");

        int countAfter = accountManager.getAccountsByType(KerioAccountConstants.ACCOUNT_TYPE).length;
        Toast.makeText(
                this,
                "Konto gespeichert. Kerio-Accounts im System: " + countAfter,
                Toast.LENGTH_LONG).show();

        if (mAccountAuthenticatorResponse != null) {
            Bundle result = new Bundle();
            result.putString(AccountManager.KEY_ACCOUNT_NAME, account.name);
            result.putString(AccountManager.KEY_ACCOUNT_TYPE, account.type);
            mAccountAuthenticatorResponse.onResult(result);
        }

        setResult(RESULT_OK);
        finish();
    }

    // ------------------------------------------------------------------------
    // Runtime-Permissions
    // ------------------------------------------------------------------------

    /**
     * @brief Prüft ob alle erforderlichen Runtime-Permissions erteilt sind
     *        Prüft folgende Permissions (ab Android 6.0 / API 23):
     *        - READ_CALENDAR und WRITE_CALENDAR (für Kalender-Sync)
     *        - READ_CONTACTS und WRITE_CONTACTS (für Kontakt-Sync)
     *        - GET_ACCOUNTS (für Account-Manager-Zugriff)
     *
     *        Für API < 23 gibt diese Methode immer true zurück
     *        (Manifest-Permissions sind auf älteren Versionen ausreichend).
     *
     * @return true wenn alle Permissions gewährt, false sonst
     */
    private boolean hasAllRequiredPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }

        boolean hasReadCalendar = ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
        boolean hasWriteCalendar = ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED;

        boolean hasReadContacts = ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
        boolean hasWriteContacts = ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED;

        boolean hasGetAccounts = ContextCompat.checkSelfPermission(
                this, Manifest.permission.GET_ACCOUNTS) == PackageManager.PERMISSION_GRANTED;

        return hasReadCalendar && hasWriteCalendar
                && hasReadContacts && hasWriteContacts
                && hasGetAccounts;
    }

    /**
     * @brief Prüft ob aktuell ein bestehendes Konto bearbeitet wird
     *        Wird verwendet um zu verhindern, dass DEV-Preset-Werte (aus
     *        BuildConfig)
     *        bestehende Kontodaten überschreiben.
     *
     * @return true wenn Bearbeitung eines existierenden Kontos, false für
     *         Neuerstellung
     */
    private boolean checkIfAccountExists() {
        if (!mIsNewAccount)
            return true;
        return mExistingAccount != null;
    }

    /**
     * @brief Fordert Runtime-Permissions an, falls nicht erteilt
     *        Wichtig: Bei Sync-Adaptern versucht Android den jeweiligen Provider
     *        (Kalender/Kontakte) zu öffnen, bevor onPerformSync() überhaupt läuft.
     *        Fehlen die Runtime-Permissions, schlägt der Sync bereits in
     *        SyncManager/
     *        ContentProviderHelper mit "Permission Denial" fehl.
     *
     *        Deshalb müssen die Permissions vor dem Aktivieren/Anstoßen des Syncs
     *        erteilt sein.
     *        Diese Methode:
     *        1. Prüft hasAllRequiredPermissions()
     *        2. Falls alle erteilt: LogCat-Info und Return
     *        3. Falls fehlend: ActivityCompat.requestPermissions() mit
     *        REQ_PERMISSIONS_ALL
     *
     *        Ergebnis wird in onRequestPermissionsResult() verarbeitet.
     */
    private void ensureRequiredPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }

        if (hasAllRequiredPermissions()) {
            Log.d(TAG, "Alle erforderlichen Berechtigungen bereits vorhanden.");
            return;
        }

        Log.d(TAG, "Fordere erforderliche Berechtigungen an (READ/WRITE_CALENDAR, READ/WRITE_CONTACTS, GET_ACCOUNTS).");
        ActivityCompat.requestPermissions(
                this,
                new String[] {
                        Manifest.permission.READ_CALENDAR,
                        Manifest.permission.WRITE_CALENDAR,
                        Manifest.permission.READ_CONTACTS,
                        Manifest.permission.WRITE_CONTACTS,
                        Manifest.permission.GET_ACCOUNTS
                },
                REQ_PERMISSIONS_ALL);
    }

    /**
     * @brief Verarbeitet das Ergebnis der Permission-Anfrage
     *        Callback nach user-gesteuerte Permission-Dialog-Entscheidung:
     *        1. Prüft ob alle Permissions in REQ_PERMISSIONS_ALL gewährt wurden
     *        2. Falls ja:
     *        - Zeigt Erfolgs-Toast
     *        - Falls saveAccount() in mPendingApplySchedulerAccount wartet:
     *        Ruft KerioSyncScheduler.applyAll() auf und completeSaveAndFinish()
     *        3. Falls nein:
     *        - Zeigt Fehler-Toast mit Hinweis auf erforderliche Permissions
     *
     * @param requestCode  Permission-Request-Identifikator (wird mit
     *                     REQ_PERMISSIONS_ALL verglichen)
     * @param permissions  Array der angeforderten Permissions
     * @param grantResults Array der Grant-Ergebnisse pro Permission
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_PERMISSIONS_ALL) {
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
                        Toast.LENGTH_LONG).show();

                // Wenn saveAccount() bereits gespeichert hat, aber Permissions fehlten:
                if (mPendingApplySchedulerAccount != null) {
                    Account pending = mPendingApplySchedulerAccount;
                    mPendingApplySchedulerAccount = null;

                    try {
                        KerioSyncScheduler.applyAll(this, pending);
                    } catch (Exception e) {
                        Log.e(TAG, "Scheduler konnte nach Permission-Grant nicht angewendet werden: " + e.getMessage(),
                                e);
                    }

                    AccountManager am = AccountManager.get(this);
                    completeSaveAndFinish(am, pending);
                    return;
                }
            } else {
                Log.w(TAG, "Mindestens eine benötigte Berechtigung wurde verweigert.");
                Toast.makeText(
                        this,
                        "Ohne Kalender-Berechtigungen kann KerioSync keine Termine synchronisieren.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // ------------------------------------------------------------------------
    // SSL / Zertifikats-UI
    // ------------------------------------------------------------------------

    /**
     * @brief Aktualisiert Aktivierungszustand der CA-Zertifikat-Kontrollen
     *        Wenn "Trust All Certificates" aktiviert:
     *        - CA-Picker-Button deaktiviert
     *        - CA-Clearer-Button deaktiviert
     *
     *        Wenn "Trust All Certificates" deaktiviert:
     *        - Beide Buttons aktiviert für benutzerdefinierte CA-Verwaltung
     */
    private void updateCaControlsEnabled() {
        boolean trustAll = chkTrustAllCerts.isChecked();
        btnPickCustomCa.setEnabled(!trustAll);
        btnClearCustomCa.setEnabled(!trustAll);
    }

    /**
     * @brief Aktualisiert CA-Zertifikat-Info-Textanzeige
     *        Zeigt entweder:
     *        - Hinweis auf Verwendung von System-Zertifikatsspeicher (uri == null)
     *        - Uri des ausgewählten benutzerdefinierten Zertifikats (uri != null)
     *
     * @param uri Uri des CA-Zertifikats oder null für System-Standard
     */
    private void updateCaInfoText(@Nullable Uri uri) {
        if (uri == null) {
            txtCustomCaInfo
                    .setText("Kein eigenes Zertifikat ausgewählt. Es wird der System-Zertifikatsspeicher verwendet.");
        } else {
            txtCustomCaInfo.setText("Eigenes Zertifikat ausgewählt:\n" + uri.toString());
        }
    }
}
