/**
 * @file ManagedConfig.java
 * @brief Zentrale Verwaltung von MDM-Konfigurationen (App Restrictions)
 * 
 * @author Simon Marcel Linden
 * @date 2026
 * @version 0.9.8
 */
package de.schulz.keriosync.mdm;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.content.RestrictionsManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

import de.schulz.keriosync.auth.KerioAccountConstants;
import de.schulz.keriosync.sync.KerioSyncScheduler;

/**
 * @class ManagedConfig
 * @brief Zentraler Zugriff auf Managed Configurations aus MDM-Systemen
 *
 *        Diese Utility-Klasse verwaltet den Zugriff auf App Restrictions
 *        (Managed Configurations),
 *        die von einem Mobile Device Management (MDM) System gesetzt werden
 *        können.
 * 
 *        Hauptfunktionen:
 *        - Robustes Auslesen von MDM-Konfigurationen mit Standardwerten
 *        - Erkennung von Konfigurationsänderungen mittels Checksum-Vergleich
 *        - Automatisches Anwenden von Konfigurationen auf bestehende Accounts
 *        - Optional: Automatisches Anlegen von Accounts basierend auf
 *        MDM-Vorgaben
 *        - Auslösen von Synchronisationen bei Konfigurationsänderungen
 * 
 *        Die Restriction-Keys müssen mit den Definitionen in restrictions.xml
 *        übereinstimmen.
 * 
 * @note Diese Klasse kann nicht instanziiert werden (privater Konstruktor)
 * @see android.content.RestrictionsManager
 * @see KerioAccountConstants
 * @see KerioSyncScheduler
 */
public final class ManagedConfig {

    private static final String TAG = "KerioManagedConfig";

    /**
     * @brief Name der SharedPreferences-Datei für MDM-Konfiguration
     * 
     *        Wird verwendet, um die letzte Checksum der Konfiguration zu speichern.
     */
    private static final String PREFS_NAME = "keriosync_managedcfg";

    /**
     * @brief Preference-Key für die letzte Checksum
     * 
     *        Speichert die CRC32-Checksum der zuletzt angewendeten Konfiguration.
     */
    private static final String PREF_LAST_CHECKSUM = "last_checksum";

    // -------------------------------------------------------------------------
    // Restriction Keys (müssen zu restrictions.xml passen)
    // -------------------------------------------------------------------------

    /**
     * @brief MDM-Key für die Kerio-Server-Basis-URL
     */
    public static final String R_BASE_URL = "kerio_base_url";

    /**
     * @brief MDM-Key für den Benutzernamen
     */
    public static final String R_USERNAME = "kerio_username";

    /**
     * @brief MDM-Key für das Passwort
     */
    public static final String R_PASSWORD = "kerio_password";

    /**
     * @brief MDM-Key für den expliziten Account-Namen
     * 
     *        Überschreibt den Standard-Account-Namen (normalerweise gleich
     *        Username).
     */
    public static final String R_ACCOUNT_NAME = "account_name";

    /**
     * @brief MDM-Key für automatische Account-Erstellung
     * 
     *        Wenn true, wird automatisch ein Account angelegt, falls noch keiner
     *        existiert.
     */
    public static final String R_AUTO_CREATE = "auto_create_account";

    /**
     * @brief MDM-Key für SSL Trust-All-Modus
     * 
     *        Wenn true, werden alle SSL-Zertifikate akzeptiert (unsicher).
     * @warning Nur für Entwicklung/Tests verwenden
     */
    public static final String R_SSL_TRUST_ALL = "ssl_trust_all";

    /**
     * @brief MDM-Key für benutzerdefinierte CA-Zertifikat-URI
     */
    public static final String R_SSL_CUSTOM_CA_URI = "ssl_custom_ca_uri";

    /**
     * @brief MDM-Key für Aktivierung der periodischen Synchronisation
     */
    public static final String R_PERIODIC_ENABLED = "periodic_sync_enabled";

    /**
     * @brief MDM-Key für Intervall der periodischen Synchronisation in Minuten
     */
    public static final String R_PERIODIC_MINUTES = "periodic_sync_minutes";

    /**
     * @brief MDM-Key für Aktivierung der Sofort-Synchronisation
     */
    public static final String R_INSTANT_ENABLED = "instant_sync_enabled";

    /**
     * @brief MDM-Key für Update-Verzögerung der Sofort-Synchronisation in Sekunden
     */
    public static final String R_INSTANT_UPDATE_DELAY = "instant_update_delay_seconds";

    /**
     * @brief MDM-Key für maximale Verzögerung der Sofort-Synchronisation in
     *        Sekunden
     */
    public static final String R_INSTANT_MAX_DELAY = "instant_max_delay_seconds";

    /**
     * @brief MDM-Key für Auslösen einer Synchronisation bei Konfigurationsänderung
     */
    public static final String R_TRIGGER_SYNC_ON_CHANGE = "trigger_sync_on_change";

    /**
     * @brief Privater Konstruktor
     * 
     *        Verhindert die Instanziierung dieser Utility-Klasse.
     */
    private ManagedConfig() {
        // Utility
    }

    // -------------------------------------------------------------------------
    // Public Getter API
    // -------------------------------------------------------------------------

    /**
     * @brief Liest die Kerio-Server-Basis-URL aus MDM
     * 
     * @param context Android-Kontext
     * @return Server-URL oder leerer String, wenn nicht konfiguriert
     */
    public static String getBaseUrl(Context context) {
        Bundle b = readRestrictions(context);
        return safeTrim(b.getString(R_BASE_URL, ""));
    }

    public static String getUsername(Context context) {
        Bundle b = readRestrictions(context);
        return safeTrim(b.getString(R_USERNAME, ""));
    }

    /**
     * @brief Liest das Passwort aus MDM
     * 
     * @param context Android-Kontext
     * @return Passwort oder leerer String, wenn nicht konfiguriert
     * @warning Passwort wird im Klartext übertragen
     */
    public static String getPassword(Context context) {
        Bundle b = readRestrictions(context);
        return safeTrim(b.getString(R_PASSWORD, ""));
    }

    public static String getAccountNameOverride(Context context) {
        Bundle b = readRestrictions(context);
        return safeTrim(b.getString(R_ACCOUNT_NAME, ""));
    }

    /**
     * @brief Prüft, ob automatische Account-Erstellung aktiviert ist
     * 
     * @param context Android-Kontext
     * @return true, wenn Accounts automatisch angelegt werden sollen
     */
    public static boolean isAutoCreateAccount(Context context) {
        Bundle b = readRestrictions(context);
        return b.getBoolean(R_AUTO_CREATE, false);
    }

    /**
     * @brief Prüft, ob SSL Trust-All-Modus aktiviert ist
     * 
     * @param context Android-Kontext
     * @return true, wenn alle SSL-Zertifikate akzeptiert werden sollen
     */
    public static boolean isTrustAllCerts(Context context) {
        Bundle b = readRestrictions(context);
        return b.getBoolean(R_SSL_TRUST_ALL, false);
    }

    /**
     * @brief Liest die URI für benutzerdefiniertes CA-Zertifikat
     * 
     * @param context Android-Kontext
     * @return CA-Zertifikat-URI oder leerer String
     */
    public static String getCustomCaUri(Context context) {
        Bundle b = readRestrictions(context);
        return safeTrim(b.getString(R_SSL_CUSTOM_CA_URI, ""));
    }

    /**
     * @brief Prüft, ob periodische Synchronisation aktiviert ist
     * 
     * @param context Android-Kontext
     * @return true, wenn periodische Sync aktiviert ist
     */
    public static boolean isPeriodicEnabled(Context context) {
        Bundle b = readRestrictions(context);
        return b.getBoolean(R_PERIODIC_ENABLED, true);
    }

    /**
     * @brief Liest das Intervall für periodische Synchronisation
     * 
     * @param context Android-Kontext
     * @return Intervall in Minuten (mindestens MIN_PERIODIC_MINUTES)
     */
    public static int getPeriodicMinutes(Context context) {
        Bundle b = readRestrictions(context);
        int v = b.getInt(R_PERIODIC_MINUTES, KerioSyncScheduler.DEFAULT_PERIODIC_MINUTES);
        if (v < KerioSyncScheduler.MIN_PERIODIC_MINUTES)
            v = KerioSyncScheduler.MIN_PERIODIC_MINUTES;
        return v;
    }

    /**
     * @brief Prüft, ob Sofort-Synchronisation aktiviert ist
     * 
     * @param context Android-Kontext
     * @return true, wenn Sofort-Sync aktiviert ist
     */
    public static boolean isInstantEnabled(Context context) {
        Bundle b = readRestrictions(context);
        return b.getBoolean(R_INSTANT_ENABLED, true);
    }

    /**
     * @brief Liest die Update-Verzögerung für Sofort-Synchronisation
     * 
     * @param context Android-Kontext
     * @return Verzögerung in Sekunden (mindestens 0)
     */
    public static int getInstantUpdateDelaySeconds(Context context) {
        Bundle b = readRestrictions(context);
        int v = b.getInt(R_INSTANT_UPDATE_DELAY, KerioSyncScheduler.DEFAULT_INSTANT_UPDATE_DELAY_SECONDS);
        if (v < 0)
            v = 0;
        return v;
    }

    /**
     * @brief Liest die maximale Verzögerung für Sofort-Synchronisation
     * 
     * @param context Android-Kontext
     * @return Maximale Verzögerung in Sekunden (mindestens 0)
     */
    public static int getInstantMaxDelaySeconds(Context context) {
        Bundle b = readRestrictions(context);
        int v = b.getInt(R_INSTANT_MAX_DELAY, KerioSyncScheduler.DEFAULT_INSTANT_MAX_DELAY_SECONDS);
        if (v < 0)
            v = 0;
        return v;
    }

    /**
     * @brief Prüft, ob Synchronisation bei Konfigurationsänderung ausgelöst werden
     *        soll
     * 
     * @param context Android-Kontext
     * @return true, wenn bei Änderungen eine Sync getriggert werden soll (Standard:
     *         true)
     */
    public static boolean isTriggerSyncOnChange(Context context) {
        Bundle b = readRestrictions(context);
        return b.getBoolean(R_TRIGGER_SYNC_ON_CHANGE, true);
    }

    // -------------------------------------------------------------------------
    // Änderungs-Erkennung & Apply
    // -------------------------------------------------------------------------

    /**
     * @brief Wendet MDM-Konfiguration auf Accounts an, wenn sich etwas geändert hat
     * 
     *        Vergleicht die aktuelle MDM-Konfiguration mit der zuletzt angewendeten
     *        (mittels Checksum). Bei Änderungen werden alle existierenden
     *        Kerio-Accounts
     *        aktualisiert. Optional wird ein Account automatisch angelegt.
     * 
     * @param context Android-Kontext
     * @return true, wenn sich die Konfiguration geändert hat und angewendet wurde
     */
    public static boolean applyToAllAccountsIfChanged(Context context) {
        if (context == null)
            return false;

        Bundle r = readRestrictions(context);
        long checksum = checksum(r);

        long last = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(PREF_LAST_CHECKSUM, -1L);

        boolean changed = (checksum != last);
        if (!changed) {
            Log.d(TAG, "applyToAllAccountsIfChanged(): keine Änderungen (checksum=" + checksum + ")");
            return false;
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(PREF_LAST_CHECKSUM, checksum)
                .apply();

        Log.i(TAG, "applyToAllAccountsIfChanged(): Änderungen erkannt -> apply (checksum=" + checksum + ")");

        applyToAllAccounts(context, r);

        if (isTriggerSyncOnChange(context)) {
            triggerExpeditedSyncAll(context);
        }

        return true;
    }

    /**
     * @brief Wendet MDM-Konfiguration auf alle Accounts an (ohne Checksum-Prüfung)
     * 
     * @param context Android-Kontext
     */
    public static void applyToAllAccounts(Context context) {
        applyToAllAccounts(context, readRestrictions(context));
    }

    /**
     * @brief Interne Methode zum Anwenden der Konfiguration auf alle Accounts
     * 
     * @param context Android-Kontext
     * @param r       Bundle mit den Restrictions
     */
    private static void applyToAllAccounts(Context context, Bundle r) {
        AccountManager am = AccountManager.get(context);
        Account[] accounts = am.getAccountsByType(KerioAccountConstants.ACCOUNT_TYPE);

        // Auto-create falls gewünscht und noch keine Accounts existieren
        if ((accounts == null || accounts.length == 0) && r.getBoolean(R_AUTO_CREATE, false)) {
            Account created = tryAutoCreateAccount(context, am, r);
            if (created != null) {
                accounts = new Account[] { created };
            }
        }

        if (accounts == null)
            accounts = new Account[0];

        for (Account a : accounts) {
            applyToSingleAccount(context, am, a, r);
        }
    }

    /**
     * @brief Wendet MDM-Konfiguration auf einen einzelnen Account an
     * 
     *        Aktualisiert Server-URL, Credentials, SSL-Einstellungen und
     *        Sync-Parameter
     *        des angegebenen Accounts basierend auf den MDM-Vorgaben.
     * 
     * @param context Android-Kontext
     * @param am      AccountManager-Instanz
     * @param account Zu aktualisierender Account
     * @param r       Bundle mit den Restrictions
     */
    private static void applyToSingleAccount(Context context, AccountManager am, Account account, Bundle r) {
        String baseUrl = safeTrim(r.getString(R_BASE_URL, ""));
        String username = safeTrim(r.getString(R_USERNAME, ""));
        String password = safeTrim(r.getString(R_PASSWORD, ""));

        boolean trustAll = r.getBoolean(R_SSL_TRUST_ALL, false);
        String customCa = safeTrim(r.getString(R_SSL_CUSTOM_CA_URI, ""));

        boolean periodicEnabled = r.getBoolean(R_PERIODIC_ENABLED, true);
        int periodicMinutes = r.getInt(R_PERIODIC_MINUTES, KerioSyncScheduler.DEFAULT_PERIODIC_MINUTES);
        if (periodicMinutes < KerioSyncScheduler.MIN_PERIODIC_MINUTES) {
            periodicMinutes = KerioSyncScheduler.MIN_PERIODIC_MINUTES;
        }

        boolean instantEnabled = r.getBoolean(R_INSTANT_ENABLED, true);
        int updDelay = r.getInt(R_INSTANT_UPDATE_DELAY, KerioSyncScheduler.DEFAULT_INSTANT_UPDATE_DELAY_SECONDS);
        int maxDelay = r.getInt(R_INSTANT_MAX_DELAY, KerioSyncScheduler.DEFAULT_INSTANT_MAX_DELAY_SECONDS);
        if (updDelay < 0)
            updDelay = 0;
        if (maxDelay < 0)
            maxDelay = 0;

        // 1) Kerio Basisdaten (nur überschreiben, wenn MDM Werte liefert)
        if (!TextUtils.isEmpty(baseUrl)) {
            am.setUserData(account, KerioAccountConstants.KEY_SERVER_URL, baseUrl);
        }
        if (!TextUtils.isEmpty(username)) {
            am.setUserData(account, KerioAccountConstants.KEY_USERNAME, username);
        }
        if (!TextUtils.isEmpty(password)) {
            // Password separat im AccountManager speichern (nicht nur UserData)
            am.setPassword(account, password);
        }

        // 2) SSL – trustAll vs customCa
        am.setUserData(account, KerioAccountConstants.KEY_SSL_TRUST_ALL, trustAll ? "1" : "0");
        if (!TextUtils.isEmpty(customCa) && !trustAll) {
            am.setUserData(account, KerioAccountConstants.KEY_SSL_CUSTOM_CA_URI, customCa);
        } else {
            // wenn trustAll aktiv oder kein CA gesetzt -> CA leeren
            am.setUserData(account, KerioAccountConstants.KEY_SSL_CUSTOM_CA_URI, "");
        }

        // 3) Sync Settings in Account UserData (Scheduler liest diese Keys)
        am.setUserData(account, KerioAccountConstants.KEY_PERIODIC_SYNC_ENABLED, periodicEnabled ? "1" : "0");
        am.setUserData(account, KerioAccountConstants.KEY_SYNC_INTERVAL_MINUTES, String.valueOf(periodicMinutes));

        am.setUserData(account, KerioAccountConstants.KEY_INSTANT_SYNC_ENABLED, instantEnabled ? "1" : "0");
        am.setUserData(account, KerioAccountConstants.KEY_INSTANT_SYNC_UPDATE_DELAY_SECONDS, String.valueOf(updDelay));
        am.setUserData(account, KerioAccountConstants.KEY_INSTANT_SYNC_MAX_DELAY_SECONDS, String.valueOf(maxDelay));

        // 4) Scheduler neu anwenden
        KerioSyncScheduler.applyAll(context, account);

        Log.i(TAG, "applyToSingleAccount(): applied to " + account.name);
    }

    /**
     * @brief Versucht, einen Account automatisch zu erstellen
     * 
     *        Legt basierend auf den MDM-Vorgaben einen neuen Kerio-Account an,
     *        falls baseUrl und username konfiguriert sind.
     * 
     * @param context Android-Kontext
     * @param am      AccountManager-Instanz
     * @param r       Bundle mit den Restrictions
     * @return Neu erstellter Account oder null bei Fehler
     */
    private static Account tryAutoCreateAccount(Context context, AccountManager am, Bundle r) {
        String baseUrl = safeTrim(r.getString(R_BASE_URL, ""));
        String username = safeTrim(r.getString(R_USERNAME, ""));
        String password = safeTrim(r.getString(R_PASSWORD, ""));
        String explicitAccName = safeTrim(r.getString(R_ACCOUNT_NAME, ""));

        if (TextUtils.isEmpty(baseUrl) || TextUtils.isEmpty(username)) {
            Log.w(TAG, "tryAutoCreateAccount(): baseUrl/username fehlt -> kein Auto-Create");
            return null;
        }

        String accountName = !TextUtils.isEmpty(explicitAccName) ? explicitAccName : username;

        Account a = new Account(accountName, KerioAccountConstants.ACCOUNT_TYPE);

        boolean ok = false;
        try {
            ok = am.addAccountExplicitly(a, TextUtils.isEmpty(password) ? null : password, null);
        } catch (SecurityException se) {
            Log.e(TAG, "tryAutoCreateAccount(): addAccountExplicitly SecurityException: " + se.getMessage(), se);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "tryAutoCreateAccount(): addAccountExplicitly Fehler: " + e.getMessage(), e);
            return null;
        }

        if (!ok) {
            Log.w(TAG, "tryAutoCreateAccount(): Account existiert evtl. bereits oder addAccountExplicitly schlug fehl");
            return null;
        }

        // Nach Erstellung direkt initial anwenden
        applyToSingleAccount(context, am, a, r);

        Log.i(TAG, "tryAutoCreateAccount(): Account erstellt: " + accountName);
        return a;
    }

    /**
     * @brief Löst eine beschleunigte Synchronisation für alle Accounts aus
     * 
     * @param context Android-Kontext
     */
    private static void triggerExpeditedSyncAll(Context context) {
        AccountManager am = AccountManager.get(context);
        Account[] accounts = am.getAccountsByType(KerioAccountConstants.ACCOUNT_TYPE);
        if (accounts == null)
            accounts = new Account[0];

        for (Account a : accounts) {
            KerioSyncScheduler.requestExpeditedSync(a);
        }
        Log.i(TAG, "triggerExpeditedSyncAll(): expedited Sync für " + accounts.length + " Accounts angestoßen");
    }

    // -------------------------------------------------------------------------
    // Low-level: Restrictions lesen / Checksum
    // -------------------------------------------------------------------------

    /**
     * @brief Liest die App Restrictions vom RestrictionsManager
     * 
     * @param context Android-Kontext
     * @return Bundle mit Restrictions oder leeres Bundle bei Fehler
     */
    private static Bundle readRestrictions(Context context) {
        try {
            RestrictionsManager rm = (RestrictionsManager) context.getSystemService(Context.RESTRICTIONS_SERVICE);
            if (rm == null)
                return new Bundle();

            Bundle b = rm.getApplicationRestrictions();
            return (b != null) ? b : new Bundle();
        } catch (Exception e) {
            Log.e(TAG, "readRestrictions(): Fehler: " + e.getMessage(), e);
            return new Bundle();
        }
    }

    /**
     * @brief Berechnet eine CRC32-Checksum über alle Bundle-Einträge
     * 
     *        Die Checksum wird über einen sortierten, stabilen String aller
     *        Key-Value-Paare berechnet, um Konfigurationsänderungen zu erkennen.
     * 
     * @param b Bundle mit Restrictions
     * @return CRC32-Checksum oder -1 bei Fehler
     */
    private static long checksum(Bundle b) {
        try {
            // Stabiler String über Keys sortiert
            String[] keys = b.keySet().toArray(new String[0]);
            Arrays.sort(keys);

            StringBuilder sb = new StringBuilder();
            for (String k : keys) {
                Object v = b.get(k);
                sb.append(k).append('=').append(String.valueOf(v)).append('\n');
            }

            CRC32 crc = new CRC32();
            byte[] data = sb.toString().getBytes(StandardCharsets.UTF_8);
            crc.update(data, 0, data.length);
            return crc.getValue();
        } catch (Exception e) {
            Log.e(TAG, "checksum(): Fehler: " + e.getMessage(), e);
            return -1L;
        }
    }

    /**
     * @brief Sicheres Trimmen von Strings mit Null-Behandlung
     * 
     * @param s Zu trimmender String (kann null sein)
     * @return Getrimmter String oder leerer String, falls null
     */
    private static String safeTrim(String s) {
        return s == null ? "" : s.trim();
    }
}
