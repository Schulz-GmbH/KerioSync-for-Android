package de.schulz.keriosync.auth;

/**
 * Zentrale Konstanteinstellungen für den Kerio-Sync-Account.
 * Dadurch bleiben AccountType, Keys usw. an einer Stelle sauber definiert.
 */
public class KerioAccountConstants {

    /**
     * Account-Typ muss exakt dem Wert entsprechen,
     * der in res/xml/authenticator.xml und res/xml/sync_calendars.xml
     * definiert ist!
     *
     * <account-authenticator
     *     android:accountType="de.schulz.keriosync.account" />
     *
     * <sync-adapter
     *     android:accountType="de.schulz.keriosync.account" />
     */
    public static final String ACCOUNT_TYPE = "de.schulz.keriosync.account";

    /**
     * UserData-Key für die gespeicherte Server-URL.
     */
    public static final String KEY_SERVER_URL = "server_url";

    /**
     * Optional: Falls später weitere Felder benötigt werden.
     */
    public static final String KEY_USERNAME = "username";
    public static final String KEY_DISPLAY_NAME = "display_name";

    private KerioAccountConstants() {
        // Keine Instanz erlaubt
    }
}
