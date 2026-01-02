/**
 * @file KerioAccountConstants.java
 * @brief Zentrale Konstanten für Kerio-Sync-Accounts
 * 
 * @author Simon Marcel Linden
 * @date 2026
 * @version 0.9.8
 */
package de.schulz.keriosync.auth;

import de.schulz.keriosync.BuildConfig;

/**
 * @class KerioAccountConstants
 * @brief Zentrale Konstantendefinitionen für den Kerio-Sync-Account
 *
 *        Diese Utility-Klasse enthält alle wichtigen Konstanten für die
 *        Account-Verwaltung
 *        und Synchronisation von Kerio-Konten. Sie ist als final deklariert und
 *        kann nicht
 *        instanziiert werden.
 * 
 *        Die Konstanten umfassen:
 *        - Account-Type und Authority-Definitionen
 *        - AccountManager UserData Keys (pro Account gespeichert)
 *        - SharedPreferences Keys (global für die gesamte App)
 *        - Alias-Konstanten für Rückwärtskompatibilität
 *        - Standardwerte für Konfigurationsparameter
 * 
 * @note Diese Klasse kann nicht instanziiert werden (privater Konstruktor)
 * @see android.accounts.AccountManager
 * @see android.content.SharedPreferences
 */
public final class KerioAccountConstants {

    // ---------------------------------------------------------------------
    // Account / Authority
    // ---------------------------------------------------------------------

    /**
     * @brief Account-Typ für Kerio-Sync-Konten
     *        Der Account-Type wird über BuildConfig gesetzt und muss mit den
     *        Definitionen in authenticator.xml und sync_calendars.xml
     *        übereinstimmen.
     *        Der Wert wird pro Build-Flavor (enduser/devteam) via BuildConfigField
     *        konfiguriert.
     * 
     * @see BuildConfig#KERIO_ACCOUNT_TYPE
     */
    public static final String ACCOUNT_TYPE = BuildConfig.KERIO_ACCOUNT_TYPE;

    /**
     * @brief Authority für den Android Calendar Provider
     *        Standard-Authority des Android-Systems für Kalender-Operationen.
     */
    public static final String CALENDAR_AUTHORITY = "com.android.calendar";

    // ---------------------------------------------------------------------
    // AccountManager UserData Keys (persistiert pro Account)
    // ---------------------------------------------------------------------

    /**
     * @brief Server-URL des Kerio-Servers
     * 
     *        UserData-Key für die Basis-URL des Kerio-Servers (z.B.
     *        "https://kerio.example.com").
     */
    public static final String KEY_SERVER_URL = "server_url";

    /**
     * @brief Benutzername für das Kerio-Konto
     * 
     *        UserData-Key für den Benutzernamen, der für die Authentifizierung
     *        verwendet wird.
     */
    public static final String KEY_USERNAME = "username";

    /**
     * @brief Anzeigename des Benutzers
     * 
     *        UserData-Key für einen benutzerfreundlichen Anzeigenamen.
     */
    public static final String KEY_DISPLAY_NAME = "display_name";

    /**
     * @brief SSL Trust-All-Modus
     * 
     *        UserData-Key für den SSL-Vertrauensmodus.
     *        Wert "1" bedeutet: alle SSL-Zertifikate werden akzeptiert (unsicher,
     *        nur für Tests).
     *        Anderer Wert oder nicht gesetzt: normale SSL-Validierung.
     * 
     * @warning Trust-All-Modus ist unsicher und sollte nur für Entwicklung/Tests
     *          verwendet werden
     */
    public static final String KEY_SSL_TRUST_ALL = "ssl_trust_all";

    /**
     * @brief URI für benutzerdefiniertes CA-Zertifikat
     * 
     *        UserData-Key für die URI eines eigenen CA-Zertifikats, das für die
     *        SSL-Validierung verwendet werden soll.
     */
    public static final String KEY_SSL_CUSTOM_CA_URI = "ssl_custom_ca_uri";

    /**
     * @brief Aktivierungsstatus der periodischen Synchronisation
     * 
     *        UserData-Key für die Aktivierung der periodischen Synchronisation.
     *        Werte: "1" = aktiviert, "0" = deaktiviert.
     */
    public static final String KEY_PERIODIC_SYNC_ENABLED = "periodic_sync_enabled";

    /**
     * @brief Intervall für periodische Synchronisation
     * 
     *        UserData-Key für das Synchronisationsintervall in Minuten (als
     *        String).
     */
    public static final String KEY_SYNC_INTERVAL_MINUTES = "sync_interval_minutes";

    /**
     * @brief Aktivierungsstatus der Sofort-Synchronisation
     * 
     *        UserData-Key für die Aktivierung der sofortigen Synchronisation bei
     *        lokalen Änderungen.
     *        Werte: "1" = aktiviert, "0" = deaktiviert.
     */
    public static final String KEY_INSTANT_SYNC_ENABLED = "instant_sync_enabled";

    /**
     * @brief Update-Verzögerung für JobScheduler
     * 
     *        UserData-Key für die Verzögerung (in Sekunden), bevor der JobScheduler
     *        eine Synchronisation nach lokalen Änderungen startet.
     */
    public static final String KEY_INSTANT_SYNC_UPDATE_DELAY_SECONDS = "instant_sync_update_delay_seconds";

    /**
     * @brief Maximale Verzögerung für JobScheduler
     * 
     *        UserData-Key für die maximale Verzögerung (in Sekunden), die der
     *        JobScheduler
     *        wartet, bevor eine Synchronisation erzwungen wird.
     */
    public static final String KEY_INSTANT_SYNC_MAX_DELAY_SECONDS = "instant_sync_max_delay_seconds";

    // ---------------------------------------------------------------------
    // Alias-Keys (Kompatibilität)
    // ---------------------------------------------------------------------

    /**
     * @brief Alias für KEY_SSL_TRUST_ALL
     *        Kompatibilitäts-Alias für alte Code-Stellen, die noch die vorherige
     *        Bezeichnung verwenden. Verwenden Sie stattdessen KEY_SSL_TRUST_ALL
     * @deprecated
     * @see #KEY_SSL_TRUST_ALL
     */
    @Deprecated
    public static final String KEY_TRUST_ALL = KEY_SSL_TRUST_ALL;

    /**
     * @brief Alias für KEY_SSL_CUSTOM_CA_URI
     *        Kompatibilitäts-Alias für alte Code-Stellen, die noch die vorherige
     *        Bezeichnung verwenden. Verwenden Sie stattdessen KEY_SSL_CUSTOM_CA_URI
     * 
     * @deprecated
     * @see #KEY_SSL_CUSTOM_CA_URI
     */
    @Deprecated
    public static final String KEY_CUSTOM_CA_URI = KEY_SSL_CUSTOM_CA_URI;

    // ---------------------------------------------------------------------
    // SharedPreferences Keys (global / nicht pro Account)
    // ---------------------------------------------------------------------

    /**
     * @brief Name der SharedPreferences-Datei
     * 
     *        Dateiname für die globalen App-Einstellungen, die nicht
     *        account-spezifisch sind.
     */
    public static final String PREFS_NAME = "keriosync_prefs";

    /**
     * @brief Aktivierung der sofortigen Synchronisation
     * 
     *        SharedPreferences-Key: Wenn true, werden Änderungen im
     *        CalendarContract.Events
     *        automatisch eine Synchronisation auslösen.
     */
    public static final String PREF_IMMEDIATE_SYNC_ENABLED = "pref_immediate_sync_enabled";

    /**
     * @brief Standardwert für sofortige Synchronisation
     * 
     *        Standardmäßig ist die sofortige Synchronisation aktiviert.
     */
    public static final boolean DEFAULT_IMMEDIATE_SYNC_ENABLED = true;

    /**
     * @brief Verzögerung für sofortige Synchronisation
     * 
     *        SharedPreferences-Key für die Debounce-Verzögerung in Sekunden, bevor
     *        eine
     *        Synchronisation nach lokalen Änderungen tatsächlich ausgelöst wird.
     */
    public static final String PREF_IMMEDIATE_SYNC_DELAY_SECONDS = "pref_immediate_sync_delay_seconds";

    /**
     * @brief Standardwert für Update-Verzögerung
     * 
     *        Standard-Verzögerung von 5 Sekunden vor Auslösung der Synchronisation.
     */
    public static final int DEFAULT_IMMEDIATE_SYNC_DELAY_SECONDS = 5;

    /**
     * @brief Zeitstempel für Unterdrückung von Change-Triggern
     * 
     *        SharedPreferences-Key: Zeitstempel (in Millisekunden), bis zu dem
     *        Change-Trigger unterdrückt werden sollen (Suppress Window).
     */
    public static final String PREF_SUPPRESS_CHANGE_TRIGGERS_UNTIL_MS = "pref_suppress_change_triggers_until_ms";

    /**
     * @brief Debug-Information: Grund der Trigger-Unterdrückung
     * 
     *        SharedPreferences-Key für Debug-Zwecke: Speichert den Grund, warum
     *        Change-Trigger aktuell unterdrückt werden.
     */
    public static final String PREF_SUPPRESS_REASON = "pref_suppress_reason";

    /**
     * @brief Privater Konstruktor
     * 
     *        Verhindert die Instanziierung dieser Utility-Klasse, da sie nur
     *        statische Konstanten enthält.
     */
    private KerioAccountConstants() {
        // Keine Instanzen erlaubt
    }
}
