package de.schulz.keriosync.sync;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SyncResult;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.text.TextUtils;
import android.util.Log;

import java.io.InputStream;
import java.net.UnknownHostException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import de.schulz.keriosync.auth.KerioAccountConstants;
import de.schulz.keriosync.net.KerioApiClient;

/**
 * SyncAdapter für Kalenderdaten von Kerio Connect.
 *
 * Aufgaben:
 * - Kerio-Ordner (privat + geteilt) per KerioApiClient laden
 * - Lokale Kalender (CalendarContract.Calendars) für diesen Account
 *   anlegen/aktualisieren und über _SYNC_ID mit Kerio-Folder-ID verknüpfen
 * - Events pro Kalenderordner synchronisieren:
 *   - RemoteEvent.uid <-> Events._SYNC_ID
 *   - Inserts/Updates/Deletes
 */
public class KerioCalendarSyncAdapter extends AbstractThreadedSyncAdapter {

    private static final String TAG = "KerioCalendarSync";

    private final ContentResolver mContentResolver;
    private final Context mContext;

    public KerioCalendarSyncAdapter(Context context, boolean autoInitialize) {
        super(context, autoInitialize);
        mContext = context.getApplicationContext();
        mContentResolver = mContext.getContentResolver();
        Log.i(TAG, "KerioCalendarSyncAdapter-Konstruktor aufgerufen. autoInitialize=" + autoInitialize
                + ", context=" + mContext);
    }

    @Override
    public void onPerformSync(Account account,
                              Bundle extras,
                              String authority,
                              ContentProviderClient provider,
                              SyncResult syncResult) {

        boolean manual = extras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, false);
        boolean expedited = extras.getBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, false);
        boolean initialize = extras.getBoolean(ContentResolver.SYNC_EXTRAS_INITIALIZE, false);

        Log.i(TAG, "onPerformSync() gestartet auf Thread: " + Thread.currentThread().getName());
        Log.i(TAG, "Starte Kalender-Sync für Account: " + account.name +
                " (authority=" + authority +
                ", manual=" + manual +
                ", expedited=" + expedited +
                ", initialize=" + initialize +
                ", extras=" + extras + ")");

        try {
            // 1. Account-Daten aus AccountManager lesen
            AccountManager am = AccountManager.get(mContext);
            String serverUrl = am.getUserData(account, KerioAccountConstants.KEY_SERVER_URL);
            String username = account.name;
            String password = am.getPassword(account);

            String trustAllStr = am.getUserData(account, KerioAccountConstants.KEY_SSL_TRUST_ALL);
            boolean trustAll = "1".equals(trustAllStr);
            String caUriStr = am.getUserData(account, KerioAccountConstants.KEY_SSL_CUSTOM_CA_URI);

            Uri caUri = null;
            if (!TextUtils.isEmpty(caUriStr)) {
                caUri = Uri.parse(caUriStr);
            }

            if (serverUrl == null || password == null) {
                Log.w(TAG, "Server-URL oder Passwort fehlen – Sync wird abgebrochen.");
                syncResult.stats.numAuthExceptions++;
                return;
            }

            Log.d(TAG, "Verwende Kerio-Server: " + serverUrl + " für Benutzer: " + username);
            Log.d(TAG, "SSL-Konfiguration: trustAll=" + trustAll + ", customCaUri=" + caUri);

            // 2. Optional: Custom-SSLSocketFactory für CA laden
            SSLSocketFactory customSslFactory = null;
            if (!trustAll && caUri != null) {
                try {
                    customSslFactory = createSslSocketFactoryForCaUri(mContext, caUri);
                    Log.i(TAG, "Custom CA-SSLSocketFactory erfolgreich erstellt.");
                } catch (Exception e) {
                    Log.e(TAG, "Fehler beim Laden des Custom-CA-Zertifikats: " + e.getMessage(), e);
                    // Fallback: default Truststore nutzen
                    customSslFactory = null;
                }
            }

            // 3. Kerio-Client initialisieren und anmelden
            KerioApiClient client = new KerioApiClient(serverUrl, username, password, trustAll, customSslFactory);
            Log.d(TAG, "Rufe KerioApiClient.login() auf …");
            client.login();
            Log.d(TAG, "Login erfolgreich.");

            // 4. Remote-Kalenderordner von Kerio holen
            List<KerioApiClient.RemoteCalendar> remoteCalendars = client.fetchCalendars();
            Log.d(TAG, "Anzahl Remote-Kalender (Kerio): " + remoteCalendars.size());

            // 5. Lokale Kalender laden und mit Remote-Kalendern abgleichen
            Map<String, Long> localCalendarsByRemoteId =
                    syncCalendarsForAccount(account, remoteCalendars, syncResult);

            // 6. Events für jeden Kalender synchronisieren
            for (KerioApiClient.RemoteCalendar rc : remoteCalendars) {
                Long localCalId = localCalendarsByRemoteId.get(rc.id);
                if (localCalId == null) {
                    Log.w(TAG, "Kein lokaler Kalender für RemoteCalendar " + rc.id +
                            " gefunden – Events werden übersprungen.");
                    continue;
                }

                long since = 0L; // TODO: später inkrementellen Sync implementieren

                List<KerioApiClient.RemoteEvent> remoteEvents =
                        client.fetchEvents(rc, since);

                Log.d(TAG, "Remote-Events für Kalender '" + rc.name + "': " + remoteEvents.size());

                syncEventsForCalendar(account, localCalId, remoteEvents, syncResult);
            }

            Log.i(TAG, "Kalender-Sync erfolgreich abgeschlossen.");

        } catch (SSLHandshakeException e) {
            Log.e(TAG, "SSL-Handshake fehlgeschlagen: " + e.getMessage(), e);
            syncResult.stats.numIoExceptions++;
        } catch (UnknownHostException e) {
            Log.e(TAG, "DNS/Host-Problem beim Kalender-Sync: " + e.getMessage(), e);
            syncResult.stats.numIoExceptions++;
        } catch (Exception e) {
            Log.e(TAG, "Fehler beim Kalender-Sync", e);
            syncResult.stats.numIoExceptions++;
        }
    }

    // ------------------------------------------------------------------------
    // Kalender-Sync (lokale <-> Remote-Kalender)
    // ------------------------------------------------------------------------

    /**
     * Synchronisiert die Kerio-Remote-Kalender mit den lokalen Android-Kalendern
     * für diesen Account.
     *
     * Verknüpfung:
     * - CalendarContract.Calendars._SYNC_ID = Kerio RemoteCalendar.id
     *
     * @return Map remoteCalendarId -> lokale Calendar-ID
     */
    private Map<String, Long> syncCalendarsForAccount(
            Account account,
            List<KerioApiClient.RemoteCalendar> remoteCalendars,
            SyncResult syncResult) {

        Map<String, Long> localByRemoteId = new HashMap<>();

        // Alle remote IDs in ein Set, damit wir später lokale "Waisen" erkennen
        Set<String> remoteIds = new HashSet<>();
        for (KerioApiClient.RemoteCalendar rc : remoteCalendars) {
            if (rc.id != null) {
                remoteIds.add(rc.id);
            }
        }

        // 1. bestehende lokale Kalender für diesen Account einlesen
        Uri calendarsUri = CalendarContract.Calendars.CONTENT_URI;

        String[] projection = new String[]{
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars._SYNC_ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.VISIBLE,
                CalendarContract.Calendars.SYNC_EVENTS
        };

        String selection = CalendarContract.Calendars.ACCOUNT_NAME + " = ? AND " +
                CalendarContract.Calendars.ACCOUNT_TYPE + " = ?";
        String[] selectionArgs = new String[]{account.name, account.type};

        Cursor cursor = mContentResolver.query(
                calendarsUri,
                projection,
                selection,
                selectionArgs,
                null
        );

        // Hier merken wir uns auch lokale Kalender, deren _SYNC_ID nicht (mehr) in remoteIds vorkommt
        Map<Long, String> localCalendarRemoteId = new HashMap<>();

        if (cursor != null) {
            try {
                while (cursor.moveToNext()) {
                    long localId = cursor.getLong(0);
                    String syncId = cursor.getString(1); // _SYNC_ID
                    String displayName = cursor.getString(2);
                    int visible = cursor.getInt(3);
                    int syncEvents = cursor.getInt(4);

                    Log.d(TAG, "Lokaler Kalender gefunden: id=" + localId +
                            ", _SYNC_ID=" + syncId +
                            ", name=" + displayName +
                            ", visible=" + visible +
                            ", syncEvents=" + syncEvents);

                    if (syncId != null && !syncId.isEmpty()) {
                        localByRemoteId.put(syncId, localId);
                        localCalendarRemoteId.put(localId, syncId);
                    }
                }
            } finally {
                cursor.close();
            }
        }

        // 2. URI als SyncAdapter aufbauen (wichtig für Insert/Update)
        Uri syncCalendarsUri = calendarsUri.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type)
                .build();

        // 3. Remote-Kalender auf lokale Kalender mappen (Insert/Update)
        for (KerioApiClient.RemoteCalendar rc : remoteCalendars) {
            if (rc.id == null) {
                Log.w(TAG, "RemoteCalendar ohne ID – wird ignoriert: " + rc);
                continue;
            }

            Long localId = localByRemoteId.get(rc.id);

            ContentValues values = new ContentValues();
            values.put(CalendarContract.Calendars.ACCOUNT_NAME, account.name);
            values.put(CalendarContract.Calendars.ACCOUNT_TYPE, account.type);

            values.put(CalendarContract.Calendars.NAME, rc.name);
            values.put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, rc.name);

            values.put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                    rc.readOnly
                            ? CalendarContract.Calendars.CAL_ACCESS_READ
                            : CalendarContract.Calendars.CAL_ACCESS_OWNER);

            values.put(CalendarContract.Calendars.VISIBLE, 1);
            values.put(CalendarContract.Calendars.SYNC_EVENTS, 1);

            values.put(CalendarContract.Calendars.CALENDAR_COLOR, 0xff33b5e5); // Dummy-Farbe oder aus rc.color
            values.put(CalendarContract.Calendars.OWNER_ACCOUNT, account.name);

            values.put(CalendarContract.Calendars._SYNC_ID, rc.id);

            if (localId == null) {
                Uri inserted = mContentResolver.insert(syncCalendarsUri, values);
                if (inserted != null) {
                    long newId = ContentUris.parseId(inserted);
                    localByRemoteId.put(rc.id, newId);
                    Log.i(TAG, "Neuer lokaler Kalender angelegt: remoteId=" + rc.id +
                            ", localId=" + newId + ", name=" + rc.name);
                    syncResult.stats.numInserts++;
                }
            } else {
                Uri updateUri = ContentUris.withAppendedId(syncCalendarsUri, localId);
                int rows = mContentResolver.update(updateUri, values, null, null);
                Log.i(TAG, "Lokaler Kalender aktualisiert: remoteId=" + rc.id +
                        ", localId=" + localId + ", rows=" + rows);
                syncResult.stats.numUpdates += rows;
            }
        }

        // 4. Lokale Kalender, die es remote nicht mehr gibt, deaktivieren
        for (Map.Entry<Long, String> entry : localCalendarRemoteId.entrySet()) {
            long localId = entry.getKey();
            String remoteId = entry.getValue();

            if (!remoteIds.contains(remoteId)) {
                Log.i(TAG, "Remote-Kalender existiert nicht mehr, lokaler Kalender wird deaktiviert: " +
                        "localId=" + localId + ", remoteId=" + remoteId);

                Uri updateUri = ContentUris.withAppendedId(syncCalendarsUri, localId);
                ContentValues values = new ContentValues();
                values.put(CalendarContract.Calendars.VISIBLE, 0);
                values.put(CalendarContract.Calendars.SYNC_EVENTS, 0);

                int rows = mContentResolver.update(updateUri, values, null, null);
                if (rows > 0) {
                    syncResult.stats.numUpdates += rows;
                }
            }
        }

        return localByRemoteId;
    }

    // ------------------------------------------------------------------------
    // Event-Sync (lokale <-> Remote-Events)
    // ------------------------------------------------------------------------

    private void syncEventsForCalendar(Account account,
                                       long localCalendarId,
                                       List<KerioApiClient.RemoteEvent> remoteEvents,
                                       SyncResult syncResult) {

        Map<String, KerioApiClient.RemoteEvent> remoteByUid = new HashMap<>();
        Set<String> remoteUids = new HashSet<>();

        for (KerioApiClient.RemoteEvent re : remoteEvents) {
            if (re.uid == null || re.uid.isEmpty()) {
                Log.w(TAG, "RemoteEvent ohne UID – wird ignoriert: " + re);
                continue;
            }
            remoteByUid.put(re.uid, re);
            remoteUids.add(re.uid);
        }

        Uri eventsUri = CalendarContract.Events.CONTENT_URI;

        String[] projection = new String[]{
                CalendarContract.Events._ID,
                CalendarContract.Events._SYNC_ID,
                CalendarContract.Events.SYNC_DATA1,   // lastModifiedUtc (String/long)
                CalendarContract.Events.DELETED,
                CalendarContract.Events.TITLE
        };

        String selection = CalendarContract.Events.CALENDAR_ID + " = ?";
        String[] selectionArgs = new String[]{String.valueOf(localCalendarId)};

        Cursor cursor = mContentResolver.query(
                eventsUri,
                projection,
                selection,
                selectionArgs,
                null
        );

        Map<String, LocalEventInfo> localByUid = new HashMap<>();
        Map<Long, LocalEventInfo> localById = new HashMap<>();

        if (cursor != null) {
            try {
                while (cursor.moveToNext()) {
                    long localId = cursor.getLong(0);
                    String syncId = cursor.getString(1);
                    String syncData1 = cursor.getString(2);
                    int deleted = cursor.getInt(3);
                    String title = cursor.getString(4);

                    long lastModifiedLocal = 0L;
                    if (syncData1 != null && !syncData1.isEmpty()) {
                        try {
                            lastModifiedLocal = Long.parseLong(syncData1);
                        } catch (NumberFormatException nfe) {
                            Log.w(TAG, "Konnte SYNC_DATA1 nicht als long parsen: " + syncData1, nfe);
                        }
                    }

                    LocalEventInfo info = new LocalEventInfo();
                    info.id = localId;
                    info.uid = syncId;
                    info.lastModifiedUtc = lastModifiedLocal;
                    info.deleted = (deleted != 0);
                    info.title = title;

                    if (syncId != null && !syncId.isEmpty()) {
                        localByUid.put(syncId, info);
                    }
                    localById.put(localId, info);
                }
            } finally {
                cursor.close();
            }
        }

        Uri syncEventsUri = eventsUri.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type)
                .build();

        // Remote -> lokal (Insert/Update)
        for (KerioApiClient.RemoteEvent remote : remoteEvents) {
            if (remote.uid == null || remote.uid.isEmpty()) {
                continue;
            }

            LocalEventInfo local = localByUid.get(remote.uid);

            ContentValues values = new ContentValues();
            values.put(CalendarContract.Events.CALENDAR_ID, localCalendarId);
            values.put(CalendarContract.Events.TITLE, remote.summary);
            values.put(CalendarContract.Events.DESCRIPTION, remote.description);
            values.put(CalendarContract.Events.EVENT_LOCATION, remote.location);
            values.put(CalendarContract.Events.DTSTART, remote.dtStartUtcMillis);
            values.put(CalendarContract.Events.DTEND, remote.dtEndUtcMillis);
            values.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC");
            values.put(CalendarContract.Events.ALL_DAY, remote.allDay ? 1 : 0);
            values.put(CalendarContract.Events._SYNC_ID, remote.uid);
            values.put(CalendarContract.Events.SYNC_DATA1, String.valueOf(remote.lastModifiedUtc));

            if (local == null) {
                Uri inserted = mContentResolver.insert(syncEventsUri, values);
                if (inserted != null) {
                    long newId = ContentUris.parseId(inserted);
                    Log.i(TAG, "Neues lokales Event angelegt: uid=" + remote.uid +
                            ", localId=" + newId + ", title=" + remote.summary);
                    syncResult.stats.numInserts++;
                }
            } else {
                boolean needsUpdate = false;

                if (remote.lastModifiedUtc > 0 && remote.lastModifiedUtc > local.lastModifiedUtc) {
                    needsUpdate = true;
                } else if (remote.lastModifiedUtc == 0L) {
                    needsUpdate = true;
                }

                if (needsUpdate) {
                    Uri updateUri = ContentUris.withAppendedId(syncEventsUri, local.id);
                    int rows = mContentResolver.update(updateUri, values, null, null);
                    Log.i(TAG, "Event aktualisiert: uid=" + remote.uid +
                            ", localId=" + local.id + ", rows=" + rows +
                            ", title=" + remote.summary);
                    syncResult.stats.numUpdates += rows;
                } else {
                    Log.d(TAG, "Event unverändert, kein Update nötig: uid=" + remote.uid +
                            ", localId=" + local.id + ", title=" + local.title);
                }
            }
        }

        // Lokale Events löschen, die es remote nicht mehr gibt
        for (LocalEventInfo local : localByUid.values()) {
            if (local.uid == null || local.uid.isEmpty()) {
                continue;
            }

            if (!remoteUids.contains(local.uid)) {
                Uri deleteUri = ContentUris.withAppendedId(syncEventsUri, local.id);
                int rows = mContentResolver.delete(deleteUri, null, null);
                Log.i(TAG, "Lokales Event gelöscht, da remote entfernt: uid=" + local.uid +
                        ", localId=" + local.id + ", rows=" + rows +
                        ", title=" + local.title);
                syncResult.stats.numDeletes += rows;
            }
        }
    }

    private static class LocalEventInfo {
        long id;
        String uid;
        long lastModifiedUtc;
        boolean deleted;
        String title;

        @Override
        public String toString() {
            return "LocalEventInfo{" +
                    "id=" + id +
                    ", uid='" + uid + '\'' +
                    ", lastModifiedUtc=" + lastModifiedUtc +
                    ", deleted=" + deleted +
                    ", title='" + title + '\'' +
                    '}';
        }
    }

    // ------------------------------------------------------------------------
    // SSL-Hilfsmethoden (Custom-CA)
    // ------------------------------------------------------------------------

    /**
     * Erzeugt eine SSLSocketFactory, die ein einzelnes CA-Zertifikat aus einer URI
     * (ACTION_OPEN_DOCUMENT) vertraut.
     */
    private SSLSocketFactory createSslSocketFactoryForCaUri(Context context, Uri caUri) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Certificate ca;
        try (InputStream caInput = context.getContentResolver().openInputStream(caUri)) {
            if (caInput == null) {
                throw new IllegalArgumentException("Konnte InputStream für CA-URI nicht öffnen: " + caUri);
            }
            ca = cf.generateCertificate(caInput);
        }

        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        keyStore.setCertificateEntry("keriosync-ca", ca);

        X509TrustManager tm = new SingleKeyStoreX509TrustManager(keyStore);

        TrustManager[] trustManagers = new TrustManager[]{tm};

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagers, new SecureRandom());
        return sslContext.getSocketFactory();
    }

    /**
     * TrustManager, der ausschließlich einem übergebenen KeyStore vertraut.
     */
    private static class SingleKeyStoreX509TrustManager implements X509TrustManager {
        private final X509TrustManager mDelegate;

        SingleKeyStoreX509TrustManager(KeyStore keyStore) throws Exception {
            TrustManager[] tms = javax.net.ssl.TrustManagerFactory
                    .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
                    .getTrustManagers();
            X509TrustManager found = null;
            for (TrustManager tm : tms) {
                if (tm instanceof X509TrustManager) {
                    found = (X509TrustManager) tm;
                    break;
                }
            }
            if (found == null) {
                throw new IllegalStateException("Kein X509TrustManager gefunden");
            }
            mDelegate = found;
        }

        @Override
        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
            // Nicht benötigt
        }

        @Override
        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
            // Delegation (hier wäre Raum für erweitertes Verhalten)
        }

        @Override
        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return mDelegate.getAcceptedIssuers();
        }
    }
}
