package de.schulz.keriosync.auth;

/**
 * Zentrale Konstanteinstellungen für den Kerio-Sync-Account.
 * Dadurch bleiben AccountType, Keys usw. an einer Stelle sauber definiert.
 */
public final class KerioAccountConstants {

    /**
     * Zentral definierter Account-Typ für Kerio Sync.
     *
     * WICHTIG:
     * Dieser Wert muss exakt dem Wert entsprechen,
     * der in res/xml/authenticator.xml und res/xml/sync_calendars.xml
     * als android:accountType eingetragen ist.
     */
    public static final String ACCOUNT_TYPE = "de.schulz.keriosync.account";

    /**
     * UserData-Key für die gespeicherte Server-URL.
     * Beispiel: https://sh-dc1.schulz-hygiene.de
     */
    public static final String KEY_SERVER_URL = "server_url";

    /**
     * Optional: weitere Metadaten, falls benötigt (z. B. für Anzeigezwecke).
     */
    public static final String KEY_USERNAME = "username";
    public static final String KEY_DISPLAY_NAME = "display_name";

    /**
     * SSL-Konfiguration pro Account:
     *
     * - KEY_SSL_TRUST_ALL:
     *   "1"   = alle Zertifikate akzeptieren (unsicher, nur für Tests),
     *   "0"/null = normale Zertifikatsprüfung (System-Truststore / CA).
     *
     * - KEY_SSL_CUSTOM_CA_URI:
     *   URI (ACTION_OPEN_DOCUMENT) auf ein eigenes CA-Zertifikat,
     *   das für diesen Account/Server verwendet werden soll.
     */
    public static final String KEY_SSL_TRUST_ALL = "ssl_trust_all";
    public static final String KEY_SSL_CUSTOM_CA_URI = "ssl_custom_ca_uri";

    private KerioAccountConstants() {
        // Keine Instanzen erlaubt
    }
}
