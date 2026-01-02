/**
 * @file    KerioSslHelper.java
 * @brief   Hilfsfunktionen für Custom CA (Benutzerdefinierte Zertifikate) und SSL/TLS-Konfiguration
 * @author  Kerio Sync Team
 * @date    2025
 * @version 1.0
 */

package de.schulz.keriosync.sync;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.InputStream;

import javax.net.ssl.SSLSocketFactory;

import de.schulz.keriosync.net.KerioApiClient;

/**
 * @class KerioSslHelper
 * @brief Utility-Klasse für Custom CA und SSL/TLS-Zertifikat-Management
 *
 *        **Funktion:**
 *        - Lädt benutzerdefinierte CA-Zertifikate über Content-URIs (z.B.
 *        Dateipicker)
 *        - Erzeugt SSLSocketFactory mit Custom CA Vertrauen
 *        - Unterstützt X.509 PEM/CRT Zertifikatsformate
 *        - Integriert mit KerioApiClient für Server-Kommunikation
 *
 *        **Use Cases:**
 *        - Firmen-interne CAs (z.B. Self-Signed, MITM-Proxy)
 *        - Proxy-Umgebungen mit Custom Root Certificates
 *        - Enterprise-Sicherheitsrichtlinien
 *
 *        **Architektur:**
 *        - Stateless Utility (private Konstruktor)
 *        - loadCustomCaSocketFactory() als einzige Public-API
 *        - Delegation an KerioApiClient.buildCustomCaSocketFactory() für
 *        SSLContext-Aufbau
 *        - Ressourcen-Management (InputStream.close() im finally-Block)
 */
public final class KerioSslHelper {

    private static final String TAG = "KerioSslHelper";

    private KerioSslHelper() {
        // Utility
    }

    /**
     * @brief Lädt eine benutzerdefinierte CA-Zertifikatsdatei und erzeugt
     *        SSLSocketFactory
     *
     *        **Ablauf:**
     *        1. Validiert Context und URI (nicht null)
     *        2. Öffnet InputStream via ContentResolver.openInputStream(caUri)
     *        3. Delegiert an KerioApiClient.buildCustomCaSocketFactory(InputStream)
     *        4. Schließt InputStream im finally-Block
     *        5. Gibt fertige SSLSocketFactory zurück
     *
     *        **Zertifikatformat:**
     *        - X.509 PEM (-----BEGIN CERTIFICATE-----...-----END CERTIFICATE-----)
     *        - X.509 DER (binär)
     *        - CRT (Alias für PEM)
     *
     *        **Exception-Handling:**
     *        - IllegalArgumentException bei null-Parametern
     *        - IllegalStateException wenn openInputStream null liefert
     *        - Exception von KerioApiClient (Parse-Fehler, Zertifikat-Fehler)
     *
     * @param context Android Context für ContentResolver.openInputStream()
     * @param caUri   Content-URI zur CA-Zertifikatsdatei (z.B. vom Dateipicker)
     *
     * @return SSLSocketFactory mit Custom CA Vertrauen
     *
     * @throws IllegalArgumentException wenn context oder caUri null
     * @throws IllegalStateException    wenn ContentResolver.openInputStream() null
     *                                  zurückgibt
     * @throws Exception                bei Zertifikat-Parse-Fehler oder SSL-Fehler
     */
    public static SSLSocketFactory loadCustomCaSocketFactory(Context context, Uri caUri) throws Exception {
        if (context == null) {
            throw new IllegalArgumentException("context ist null");
        }
        if (caUri == null) {
            throw new IllegalArgumentException("caUri ist null");
        }

        InputStream is = null;
        try {
            is = context.getContentResolver().openInputStream(caUri);
            if (is == null) {
                throw new IllegalStateException("openInputStream(caUri) lieferte null: " + caUri);
            }

            SSLSocketFactory factory = KerioApiClient.buildCustomCaSocketFactory(is);
            Log.i(TAG, "Custom CA SSLSocketFactory erzeugt für URI: " + caUri);
            return factory;

        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
