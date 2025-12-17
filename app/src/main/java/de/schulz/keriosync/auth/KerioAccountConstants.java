package de.schulz.keriosync.auth;

/**
 * Zentrale Konstanteinstellungen für den Kerio-Sync-Account.
 *
 * Enthält:
 * - AccountManager UserData Keys (pro Account)
 * - SharedPreferences Keys (global)
 *
 * Zusätzlich sind "Alias"-Konstanten enthalten, damit ältere Code-Stellen
 * (oder frühere Namen) weiterhin kompilieren.
 */
public final class KerioAccountConstants {

    // ---------------------------------------------------------------------
    // Account / Authority
    // ---------------------------------------------------------------------

    /** Account-Type (muss zu authenticator.xml / sync_calendars.xml passen) */
    public static final String ACCOUNT_TYPE = "de.schulz.keriosync.account";

    /** Calendar Provider Authority */
    public static final String CALENDAR_AUTHORITY = "com.android.calendar";

    // ---------------------------------------------------------------------
    // AccountManager UserData Keys (persistiert pro Account)
    // ---------------------------------------------------------------------

    /** Kerio Server URL (Basis) */
    public static final String KEY_SERVER_URL = "server_url";

    /** Optional: Username/Display */
    public static final String KEY_USERNAME = "username";
    public static final String KEY_DISPLAY_NAME = "display_name";

    /** SSL: "1" = trust all (unsicher), sonst normal */
    public static final String KEY_SSL_TRUST_ALL = "ssl_trust_all";

    /** SSL: URI auf eigenes CA Zertifikat */
    public static final String KEY_SSL_CUSTOM_CA_URI = "ssl_custom_ca_uri";

    /** Periodischer Sync aktiv ("1"/"0") */
    public static final String KEY_PERIODIC_SYNC_ENABLED = "periodic_sync_enabled";

    /** Periodisches Sync-Intervall in Minuten (String) */
    public static final String KEY_SYNC_INTERVAL_MINUTES = "sync_interval_minutes";

    /** „Instant“ Sync bei lokalen Änderungen aktiv ("1"/"0") */
    public static final String KEY_INSTANT_SYNC_ENABLED = "instant_sync_enabled";

    /** JobScheduler UpdateDelay (Sekunden) */
    public static final String KEY_INSTANT_SYNC_UPDATE_DELAY_SECONDS = "instant_sync_update_delay_seconds";

    /** JobScheduler MaxDelay (Sekunden) */
    public static final String KEY_INSTANT_SYNC_MAX_DELAY_SECONDS = "instant_sync_max_delay_seconds";

    // ---------------------------------------------------------------------
    // Alias-Keys (Kompatibilität)
    // ---------------------------------------------------------------------

    /** Alias für alte Bezeichnung */
    public static final String KEY_TRUST_ALL = KEY_SSL_TRUST_ALL;

    /** Alias für alte Bezeichnung */
    public static final String KEY_CUSTOM_CA_URI = KEY_SSL_CUSTOM_CA_URI;

    // ---------------------------------------------------------------------
    // SharedPreferences Keys (global / nicht pro Account)
    // ---------------------------------------------------------------------

    /** SharedPreferences-Dateiname */
    public static final String PREFS_NAME = "keriosync_prefs";

    /**
     * Wenn true: Änderungen unter CalendarContract.Events triggern automatisch requestSync(...)
     */
    public static final String PREF_IMMEDIATE_SYNC_ENABLED = "pref_immediate_sync_enabled";

    /** Default für Immediate Sync */
    public static final boolean DEFAULT_IMMEDIATE_SYNC_ENABLED = true;

    /**
     * Debounce/Verzögerung (Sekunden)
     */
    public static final String PREF_IMMEDIATE_SYNC_DELAY_SECONDS = "pref_immediate_sync_delay_seconds";

    /** Default UpdateDelay in Sekunden */
    public static final int DEFAULT_IMMEDIATE_SYNC_DELAY_SECONDS = 5;

    /**
     * Trigger-Unterdrückung (Suppress Window)
     */
    public static final String PREF_SUPPRESS_CHANGE_TRIGGERS_UNTIL_MS = "pref_suppress_change_triggers_until_ms";

    /** Debug: Grund der Unterdrückung */
    public static final String PREF_SUPPRESS_REASON = "pref_suppress_reason";

    private KerioAccountConstants() {
        // Keine Instanzen erlaubt
    }
}
