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
 * Zentraler Zugriff auf Managed Configurations (App Restrictions) aus MDM.
 *
 * Ziele:
 * - Robust lesen (Defaults)
 * - Änderungen erkennen (Checksum)
 * - Optional: Accounts aktualisieren/auto-anlegen
 * - Scheduler/Sync anstoßen
 */
public final class ManagedConfig {

    private static final String TAG = "KerioManagedConfig";

    // Preference Schlüssel (intern)
    private static final String PREFS_NAME = "keriosync_managedcfg";
    private static final String PREF_LAST_CHECKSUM = "last_checksum";

    // Restriction Keys (müssen zu restrictions.xml passen)
    public static final String R_BASE_URL = "kerio_base_url";
    public static final String R_USERNAME = "kerio_username";
    public static final String R_PASSWORD = "kerio_password";
    public static final String R_ACCOUNT_NAME = "account_name";
    public static final String R_AUTO_CREATE = "auto_create_account";

    public static final String R_SSL_TRUST_ALL = "ssl_trust_all";
    public static final String R_SSL_CUSTOM_CA_URI = "ssl_custom_ca_uri";

    public static final String R_PERIODIC_ENABLED = "periodic_sync_enabled";
    public static final String R_PERIODIC_MINUTES = "periodic_sync_minutes";

    public static final String R_INSTANT_ENABLED = "instant_sync_enabled";
    public static final String R_INSTANT_UPDATE_DELAY = "instant_update_delay_seconds";
    public static final String R_INSTANT_MAX_DELAY = "instant_max_delay_seconds";

    public static final String R_TRIGGER_SYNC_ON_CHANGE = "trigger_sync_on_change";

    private ManagedConfig() {
        // Utility
    }

    // -------------------------------------------------------------------------
    // Public Getter API (für Rest der App)
    // -------------------------------------------------------------------------

    public static String getBaseUrl(Context context) {
        Bundle b = readRestrictions(context);
        return safeTrim(b.getString(R_BASE_URL, ""));
    }

    public static String getUsername(Context context) {
        Bundle b = readRestrictions(context);
        return safeTrim(b.getString(R_USERNAME, ""));
    }

    public static String getPassword(Context context) {
        Bundle b = readRestrictions(context);
        return safeTrim(b.getString(R_PASSWORD, ""));
    }

    public static String getAccountNameOverride(Context context) {
        Bundle b = readRestrictions(context);
        return safeTrim(b.getString(R_ACCOUNT_NAME, ""));
    }

    public static boolean isAutoCreateAccount(Context context) {
        Bundle b = readRestrictions(context);
        return b.getBoolean(R_AUTO_CREATE, false);
    }

    public static boolean isTrustAllCerts(Context context) {
        Bundle b = readRestrictions(context);
        return b.getBoolean(R_SSL_TRUST_ALL, false);
    }

    public static String getCustomCaUri(Context context) {
        Bundle b = readRestrictions(context);
        return safeTrim(b.getString(R_SSL_CUSTOM_CA_URI, ""));
    }

    public static boolean isPeriodicEnabled(Context context) {
        Bundle b = readRestrictions(context);
        return b.getBoolean(R_PERIODIC_ENABLED, true);
    }

    public static int getPeriodicMinutes(Context context) {
        Bundle b = readRestrictions(context);
        int v = b.getInt(R_PERIODIC_MINUTES, KerioSyncScheduler.DEFAULT_PERIODIC_MINUTES);
        if (v < KerioSyncScheduler.MIN_PERIODIC_MINUTES)
            v = KerioSyncScheduler.MIN_PERIODIC_MINUTES;
        return v;
    }

    public static boolean isInstantEnabled(Context context) {
        Bundle b = readRestrictions(context);
        return b.getBoolean(R_INSTANT_ENABLED, true);
    }

    public static int getInstantUpdateDelaySeconds(Context context) {
        Bundle b = readRestrictions(context);
        int v = b.getInt(R_INSTANT_UPDATE_DELAY, KerioSyncScheduler.DEFAULT_INSTANT_UPDATE_DELAY_SECONDS);
        if (v < 0)
            v = 0;
        return v;
    }

    public static int getInstantMaxDelaySeconds(Context context) {
        Bundle b = readRestrictions(context);
        int v = b.getInt(R_INSTANT_MAX_DELAY, KerioSyncScheduler.DEFAULT_INSTANT_MAX_DELAY_SECONDS);
        if (v < 0)
            v = 0;
        return v;
    }

    public static boolean isTriggerSyncOnChange(Context context) {
        Bundle b = readRestrictions(context);
        return b.getBoolean(R_TRIGGER_SYNC_ON_CHANGE, true);
    }

    // -------------------------------------------------------------------------
    // Änderungs-Erkennung & Apply
    // -------------------------------------------------------------------------

    /**
     * Wendet die Managed Config auf alle existierenden KerioSync-Accounts an.
     * Optional: legt Account automatisch an, wenn konfiguriert.
     *
     * @return true wenn sich etwas geändert hat (Checksum unterschiedlich), sonst
     *         false
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
     * Wendet Config immer an (ohne Checksum-Vergleich).
     */
    public static void applyToAllAccounts(Context context) {
        applyToAllAccounts(context, readRestrictions(context));
    }

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

    private static String safeTrim(String s) {
        return s == null ? "" : s.trim();
    }
}
