package de.schulz.keriosync.sync;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.InputStream;

import javax.net.ssl.SSLSocketFactory;

import de.schulz.keriosync.net.KerioApiClient;

/**
 * Hilfsklasse zum Laden einer benutzerdefinierten CA (z.B. Firmen-CA)
 * und zum Erzeugen einer SSLSocketFactory, die dieser CA vertraut.
 */
public final class KerioSslHelper {

    private static final String TAG = "KerioSslHelper";

    private KerioSslHelper() {
        // Utility
    }

    /**
     * Lädt eine CA-Zertifikatsdatei über eine Content-URI (z.B. aus dem Dateipicker)
     * und erzeugt daraus eine SSLSocketFactory.
     *
     * @param context Android Context
     * @param caUri   Content-URI zur CA-Datei (PEM/CRT, X.509)
     * @return SSLSocketFactory oder Exception
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
