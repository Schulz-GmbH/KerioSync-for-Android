/**
 * @file KerioCalendarSyncAdapter.java
 * @brief SyncAdapter für bidirektionale Kalender-Synchronisation mit Kerio Connect
 *
 * @author Simon Marcel Linden
 * @date 2026
 * @version 0.9.8
 */
package de.schulz.keriosync.sync;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SyncResult;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.text.TextUtils;
import android.util.Log;

import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocketFactory;

import de.schulz.keriosync.auth.KerioAccountConstants;
import de.schulz.keriosync.net.KerioApiClient;

/**
 * @class KerioCalendarSyncAdapter
 * @brief Bidirektionaler Kalender-SyncAdapter für Kerio Connect.
 *        Synchronisiert Kalender und Termine zwischen Android CalendarProvider
 *        und Kerio Connect Server. Unterstützt Public/Shared/Delegated
 *        Kalender,
 *        bidirektionales Sync (Server<->Client) sowie Update/Delete/Create.
 *
 *        Wichtige Sync-Mappings:
 *        - _SYNC_ID: Kerio Occurrence-ID (id aus Occurrences.get)
 *        - SYNC_DATA2: Kerio Event-ID (eventId)
 *        - SYNC_DATA1: lastModificationTime (Millis als String)
 *
 *        Besonderheiten:
 *        - Automatisches Resolving von Occurrence-IDs für neu erstellte Events
 *        - Reparatur-Mechanismus für Legacy-Einträge (eventId@dtStart)
 *        - Change-Trigger-Unterdrückung während Sync zur Vermeidung von Loops
 */
public class KerioCalendarSyncAdapter extends AbstractThreadedSyncAdapter {

    private static final String TAG = "KerioCalendarSync";

    private final ContentResolver mContentResolver;
    private final Context mContext;

    /**
     * @brief Cache der lokalen Kalender-Infos (pro Remote-Calendar-ID).
     *        Wird pro Sync-Lauf neu aufgebaut und genutzt, um Nutzer-Einstellungen
     *        wie
     *        SYNC_EVENTS zu respektieren.
     */
    private volatile Map<String, LocalCalendarInfo> mLocalCalendarInfoByRemoteId = new HashMap<>();

    /**
     * @class LocalCalendarInfo
     * @brief Interne Struktur für lokale Kalender-Einstellungen.
     *
     *        Cached Nutzerwerte (Farbe, Sichtbarkeit, Sync-Flag), damit diese
     *        nicht während Sync überschrieben werden.
     */
    private static class LocalCalendarInfo {
        /** Lokale CalendarContract.Calendars._ID */
        long localId;

        /** Remote Kerio FolderId (_SYNC_ID) */
        String remoteId;

        /** Nutzer-Sichtbarkeit (VISIBLE) */
        boolean visible;

        /** Nutzer-Sync-Schalter (SYNC_EVENTS) */
        boolean syncEvents;

        /** Nutzer-Farbe (CALENDAR_COLOR) – null, wenn nicht gesetzt */
        Integer calendarColor;
    }

    /**
     * @brief Konstruktor für den SyncAdapter.
     * @param context        App-Kontext
     * @param autoInitialize Auto-Initialize-Flag
     */
    public KerioCalendarSyncAdapter(Context context, boolean autoInitialize) {
        super(context, autoInitialize);
        mContext = context.getApplicationContext();
        mContentResolver = mContext.getContentResolver();
        Log.i(TAG, "KerioCalendarSyncAdapter-Konstruktor aufgerufen. autoInitialize=" + autoInitialize
                + ", context=" + mContext);
    }

    /**
     * @brief Hauptmethode für Synchronisation: Push lokale Änderungen, pull
     *        Remote-Daten.
     * @param account    Kerio-Account
     * @param extras     Sync-Parameter (manual, expedited, initialize)
     * @param authority  CalendarContract.AUTHORITY
     * @param provider   ContentProviderClient
     * @param syncResult Sync-Statistiken
     */
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

        // Unterdrücke Change-Trigger, damit eigene Inserts/Updates nicht wieder
        // triggern
        suppressChangeTriggers("sync-start:" + account.name, 30);

        try {
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

            SSLSocketFactory customSslFactory = null;
            if (!trustAll && caUri != null) {
                try {
                    customSslFactory = createSslSocketFactoryForCaUri(mContext, caUri);
                    Log.i(TAG, "Custom CA-SSLSocketFactory erfolgreich erstellt.");
                } catch (Exception e) {
                    Log.e(TAG, "Fehler beim Laden des Custom-CA-Zertifikats: " + e.getMessage(), e);
                    customSslFactory = null;
                }
            }

            KerioApiClient client = new KerioApiClient(serverUrl, username, password, trustAll, customSslFactory);
            Log.d(TAG, "Rufe KerioApiClient.login() auf …");
            client.login();
            Log.d(TAG, "Login erfolgreich.");

            List<KerioApiClient.RemoteCalendar> remoteCalendars = client.fetchCalendars();
            Log.d(TAG, "Anzahl Remote-Kalender (Kerio): " + remoteCalendars.size());

            Map<String, Long> localCalendarsByRemoteId = syncCalendarsForAccount(account, remoteCalendars, syncResult);

            for (KerioApiClient.RemoteCalendar rc : remoteCalendars) {
                Long localCalId = localCalendarsByRemoteId.get(rc.id);
                if (localCalId == null) {
                    Log.w(TAG, "Kein lokaler Kalender für RemoteCalendar " + rc.id +
                            " gefunden – Events werden übersprungen.");
                    continue;
                }

                LocalCalendarInfo localInfo = mLocalCalendarInfoByRemoteId.get(rc.id);
                if (localInfo != null && !localInfo.syncEvents) {
                    Log.i(TAG, "Kalender ist lokal auf SYNC_EVENTS=0 gesetzt – überspringe Sync für: " +
                            ((rc.displayName != null && !rc.displayName.isEmpty()) ? rc.displayName : rc.name));
                    continue;
                }

                // Push lokale Änderungen (Delete/Update/Create)
                pushLocalDeletedEvents(account, localCalId, rc, client, syncResult);
                pushLocalUpdatedEvents(account, localCalId, rc, client, syncResult);
                pushLocalCreatedEvents(account, localCalId, rc, client, syncResult);

                long since = 0L; // TODO: später inkrementellen Sync implementieren
                List<KerioApiClient.RemoteEvent> remoteEvents = client.fetchEvents(rc, since);

                if (remoteEvents == null) {
                    Log.w(TAG, "Remote-Events sind NULL für Kalender '" + rc.name + "'. Kein Delete/Upsert.");
                    continue;
                }

                Log.d(TAG, "Remote-Events für Kalender '" + rc.name + "': " + remoteEvents.size());

                if (remoteEvents.isEmpty()) {
                    Log.w(TAG, "Remote-Events sind LEER für Kalender '" + rc.name + "'. " +
                            "Wenn das unerwartet ist, stimmt meist Query/Parser/Zeitraum nicht.");
                }

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
        } finally {
            /**
             * @brief Baut eine SyncAdapter-URI für Events (CALLER_IS_SYNCADAPTER=true).
             * @param account Kerio-Account
             * @return Events-URI mit SyncAdapter-Parametern
             */
            suppressChangeTriggers("sync-end:" + account.name, 8);
        }
    }

    /**
     * @brief Baut eine SyncAdapter-URI für ein spezifisches Event.
     * @param account Kerio-Account
     * @param eventId Lokale Event-ID
     * @return Events-URI mit appended Event-ID
     */

    // ------------------------------------------------------------------------
    // Helper: SyncAdapter-Events-URI
    // ------------------------------------------------------------------------

    private Uri buildSyncAdapterEventsUri(Account account) {
        return CalendarContract.Events.CONTENT_URI.buildUpon()
                /**
                 * @brief Prüft, ob ein String eine echte Kerio Occurrence-ID ist.
                 * @param syncId _SYNC_ID-Wert
                 * @return true, wenn Format "keriostorage://occurrence/..." oder
                 *         "keriostorage:"
                 */
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type)
                .build();
    }

    /**
     * @brief Pusht lokal neu erstellte Events zum Kerio-Server (Events.create).
     *        Sucht dirty Events ohne remoteEventId und legt sie auf dem Server an.
     *        Versucht anschließend, echte Occurrence-ID zu resolven.
     * @param account         Kerio-Account
     * @param localCalendarId Lokale Kalender-ID
     * @param remoteCalendar  Remote-Kalender (für readOnly-Check)
     * @param client          KerioApiClient
     * @param syncResult      Sync-Statistiken
     */
    private Uri buildSyncAdapterEventsUri(Account account, long eventId) {
        Uri base = buildSyncAdapterEventsUri(account);
        return ContentUris.withAppendedId(base, eventId);
    }

    private boolean isLikelyKerioOccurrenceId(String syncId) {
        if (syncId == null)
            return false;
        // typische Kerio IDs: keriostorage://occurrence/...
        return syncId.startsWith("keriostorage://occurrence/") || syncId.startsWith("keriostorage:");
    }

    // ------------------------------------------------------------------------
    // Push: Lokale neu erstellte Events -> Kerio (Events.create)
    // ------------------------------------------------------------------------

    /*
     * @brief Pusht lokal neu erstellte Events zum Kerio-Server (Events.create).
     * Sucht dirty Events ohne remoteEventId und legt sie auf dem Server an.
     * Versucht anschließend, echte Occurrence-ID zu resolven.
     * 
     * @param account Kerio-Account
     * 
     * @param localCalendarId Lokale Kalender-ID
     * 
     * @param remoteCalendar Remote-Kalender (für readOnly-Check)
     * 
     * @param client KerioApiClient
     * 
     * @param syncResult Sync-Statistiken
     */
    private void pushLocalCreatedEvents(Account account,
            long localCalendarId,
            KerioApiClient.RemoteCalendar remoteCalendar,
            KerioApiClient client,
            SyncResult syncResult) {

        if (remoteCalendar != null && remoteCalendar.readOnly) {
            Log.i(TAG, "pushLocalCreatedEvents: RemoteCalendar '" + remoteCalendar.name + "' ist readOnly – Skip.");
            return;
        }

        Uri eventsUri = CalendarContract.Events.CONTENT_URI;

        String[] projection = new String[] {
                CalendarContract.Events._ID,
                CalendarContract.Events.DIRTY,
                CalendarContract.Events.DELETED,
                CalendarContract.Events.SYNC_DATA2, // remote eventId (optional)
                CalendarContract.Events._SYNC_ID, // remote occurrenceId (sollte es sein)
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.ALL_DAY,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.EVENT_LOCATION
        };

        String selection = CalendarContract.Events.CALENDAR_ID + "=? AND " +
                CalendarContract.Events.DIRTY + "=1 AND " +
                CalendarContract.Events.DELETED + "=0";

        String[] selectionArgs = new String[] { String.valueOf(localCalendarId) };

        Cursor c = null;
        try {
            c = mContentResolver.query(eventsUri, projection, selection, selectionArgs, null);
            if (c == null) {
                Log.w(TAG, "pushLocalCreatedEvents: Cursor ist NULL");
                return;
            }

            while (c.moveToNext()) {
                long localId = c.getLong(0);
                int dirty = c.getInt(1);
                int deleted = c.getInt(2);
                String remoteEventId = c.getString(3);
                String syncId = c.getString(4);

                long dtStart = c.getLong(5);
                long dtEnd = c.getLong(6);
                boolean allDay = c.getInt(7) != 0;

                String title = c.getString(8);
                String description = c.getString(9);
                String location = c.getString(10);

                if (dirty == 0 || deleted != 0)
                    continue;

                // Nur CREATE: remoteEventId fehlt
                if (!TextUtils.isEmpty(remoteEventId))
                    continue;

                if (dtStart <= 0L) {
                    Log.w(TAG, "pushLocalCreatedEvents: DTSTART fehlt, skip localId=" + localId);
                    continue;
                }
                if (dtEnd <= 0L) {
                    dtEnd = dtStart + (30L * 60L * 1000L);
                }

                KerioApiClient.RemoteEvent ev = new KerioApiClient.RemoteEvent();
                ev.summary = title;
                ev.description = description;
                ev.location = location;
                ev.allDay = allDay;
                ev.dtStartUtcMillis = dtStart;
                ev.dtEndUtcMillis = dtEnd;

                try {
                    KerioApiClient.CreateResult cr = client.createEvent(remoteCalendar, ev);
                    if (cr == null || TextUtils.isEmpty(cr.id)) {
                        Log.e(TAG, "Events.create lieferte keine ID zurück. localId=" + localId);
                        syncResult.stats.numIoExceptions++;
                        continue;
                    }

                    // Jetzt echte Occurrence-ID resolven (Events.create liefert meist Event-ID)
                    String resolvedOccurrenceId = null;
                    try {
                        resolvedOccurrenceId = client.resolveOccurrenceIdForEvent(remoteCalendar.id, cr.id, dtStart);
                    } catch (Exception ex) {
                        Log.w(TAG, "resolveOccurrenceIdForEvent fehlgeschlagen (localId=" + localId + "): "
                                + ex.getMessage(), ex);
                    }

                    ContentValues cv = new ContentValues();
                    cv.put(CalendarContract.Events.SYNC_DATA2, cr.id); // Event-ID merken

                    if (!TextUtils.isEmpty(resolvedOccurrenceId)) {
                        cv.put(CalendarContract.Events._SYNC_ID, resolvedOccurrenceId); // echte Occurrence-ID
                    } else {
                        // Fallback: NICHT ideal, aber besser als nichts – Update/Repair versucht später
                        // zu resolven
                        String fallback = cr.id + "@" + dtStart;
                        cv.put(CalendarContract.Events._SYNC_ID, fallback);
                    }

                    cv.put(CalendarContract.Events.DIRTY, 0);

                    Uri updUri = buildSyncAdapterEventsUri(account, localId);
                    int rows = mContentResolver.update(updUri, cv, null, null);

                    Log.i(TAG, "Event CREATE gepusht: localId=" + localId +
                            ", remoteEventId=" + cr.id +
                            /**
                             * @brief Pusht lokal gelöschte Events zum Kerio-Server (Occurrences.remove).
                             *
                             *        Sucht deleted Events mit _SYNC_ID und löscht sie auf dem Server.
                             *        Führt ggf. Repair durch (resolveOccurrenceId), wenn _SYNC_ID kein
                             *        echtes
                             *        Kerio-Format hat.
                             * @param account         Kerio-Account
                             * @param localCalendarId Lokale Kalender-ID
                             * @param remoteCalendar  Remote-Kalender (für Folder-ID)
                             * @param client          KerioApiClient
                             * @param syncResult      Sync-Statistiken
                             */
                            ", occurrenceId=" + resolvedOccurrenceId +
                            ", rows=" + rows +
                            ", oldSyncId=" + syncId);

                    syncResult.stats.numInserts++;

                } catch (Exception e) {
                    Log.e(TAG, "pushLocalCreatedEvents: CREATE fehlgeschlagen für localId=" + localId, e);
                    syncResult.stats.numIoExceptions++;
                }
            }

        } finally {
            if (c != null)
                c.close();
        }
    }

    // ------------------------------------------------------------------------
    // Push: Lokale gelöschte Events -> Kerio (Occurrences.remove)
    // ------------------------------------------------------------------------

    /*
     * @brief Pusht lokal gelöschte Events zum Kerio-Server (Occurrences.remove).
     * Sucht deleted Events mit _SYNC_ID und löscht sie auf dem Server.
     * Führt ggf. Repair durch (resolveOccurrenceId), wenn _SYNC_ID kein echtes
     * Kerio-Format hat.
     * 
     * @param account Kerio-Account
     * 
     * @param localCalendarId Lokale Kalender-ID
     * 
     * @param remoteCalendar Remote-Kalender (für Folder-ID)
     * 
     * @param client KerioApiClient
     * 
     * @param syncResult Sync-Statistiken
     */
    private void pushLocalDeletedEvents(Account account,
            long localCalendarId,
            KerioApiClient.RemoteCalendar remoteCalendar,
            KerioApiClient client,
            SyncResult syncResult) {

        final ContentResolver resolver = mContext.getContentResolver();

        final String[] PROJECTION = new String[] {
                CalendarContract.Events._ID,
                CalendarContract.Events._SYNC_ID,
                CalendarContract.Events.SYNC_DATA2, // eventId (optional)
                CalendarContract.Events.DTSTART
        };

        final String selection = CalendarContract.Events.CALENDAR_ID + "=? AND " +
                CalendarContract.Events.DELETED + "=1 AND " +
                CalendarContract.Events._SYNC_ID + " IS NOT NULL";

        Cursor c = null;
        try {
            c = resolver.query(CalendarContract.Events.CONTENT_URI,
                    PROJECTION,
                    selection,
                    new String[] { String.valueOf(localCalendarId) },
                    null);

            if (c == null)
                return;

            while (c.moveToNext()) {
                long localEventId = c.getLong(0);
                String occurrenceId = c.getString(1);
                String remoteEventId = c.getString(2);
                long dtStart = c.getLong(3);

                if (TextUtils.isEmpty(occurrenceId))
                    continue;

                // Reparatur: Wenn _SYNC_ID kein Kerio occurrenceId ist, versuche ihn über
                // eventId+dtStart zu resolven
                if (!isLikelyKerioOccurrenceId(occurrenceId) && !TextUtils.isEmpty(remoteEventId) && dtStart > 0) {
                    try {
                        String resolved = client.resolveOccurrenceIdForEvent(remoteCalendar.id, remoteEventId, dtStart);
                        if (!TextUtils.isEmpty(resolved)) {
                            occurrenceId = resolved;

                            // lokal direkt reparieren (damit Folgevorgänge konsistent sind)
                            ContentValues fix = new ContentValues();
                            fix.put(CalendarContract.Events._SYNC_ID, resolved);
                            resolver.update(buildSyncAdapterEventsUri(account, localEventId), fix, null, null);

                            Log.i(TAG, "DELETE Repair: localId=" + localEventId + " -> occurrenceId=" + resolved);
                        }
                    } catch (Exception ex) {
                        Log.w(TAG, "DELETE Repair resolveOccurrenceIdForEvent fehlgeschlagen: localId=" + localEventId
                                + " " + ex.getMessage(), ex);
                    }
                }

                if (!isLikelyKerioOccurrenceId(occurrenceId)) {
                    Log.w(TAG,
                            "pushLocalDeletedEvents: _SYNC_ID ist keine Occurrence-ID, DELETE wird übersprungen: localId="
                                    + localEventId + ", _SYNC_ID=" + occurrenceId);
                    continue;
                }

                try {
                    client.deleteOccurrence(occurrenceId);

                    int rows = resolver.delete(
                            buildSyncAdapterEventsUri(account, localEventId),
                            /**
                             * @brief Pusht lokal geänderte Events zum Kerio-Server (Occurrences.set).
                             *
                             *        Sucht dirty Events mit _SYNC_ID und aktualisiert sie auf dem Server.
                             *        Führt ggf. Repair durch, wenn _SYNC_ID kein echtes Kerio-Format hat.
                             * @param account         Kerio-Account
                             * @param localCalendarId Lokale Kalender-ID
                             * @param remoteCalendar  Remote-Kalender (für readOnly-Check)
                             * @param client          KerioApiClient
                             * @param syncResult      Sync-Statistiken
                             */
                            null,
                            null);

                    Log.i(TAG, "Event gelöscht (push->server): occurrenceId=" + occurrenceId +
                            ", localId=" + localEventId + ", rows=" + rows);

                    syncResult.stats.numDeletes++;

                } catch (Exception ex) {
                    Log.e(TAG, "pushLocalDeletedEvents: DELETE fehlgeschlagen für localId=" + localEventId +
                            ", occurrenceId=" + occurrenceId, ex);
                    syncResult.stats.numIoExceptions++;
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "pushLocalDeletedEvents: Fehler beim lokalen Query", e);
            syncResult.stats.numIoExceptions++;
        } finally {
            if (c != null)
                c.close();
        }
    }

    // ------------------------------------------------------------------------
    // Push: Lokale Updates an bestehenden Events -> Kerio (Occurrences.set)
    // ------------------------------------------------------------------------

    /**
     * @brief Pusht lokal geänderte Events zum Kerio-Server (Occurrences.set).
     *        Sucht dirty Events mit _SYNC_ID und aktualisiert sie auf dem Server.
     *        Führt ggf. Repair durch, wenn _SYNC_ID kein echtes Kerio-Format hat.
     * @param account         Kerio-Account
     * @param localCalendarId Lokale Kalender-ID
     * @param remoteCalendar  Remote-Kalender (für readOnly-Check)
     * @param client          KerioApiClient
     * @param syncResult      Sync-Statistiken
     */
    private void pushLocalUpdatedEvents(Account account,
            long localCalendarId,
            KerioApiClient.RemoteCalendar remoteCalendar,
            KerioApiClient client,
            SyncResult syncResult) {

        if (remoteCalendar != null && remoteCalendar.readOnly) {
            Log.i(TAG, "pushLocalUpdatedEvents: RemoteCalendar '" + remoteCalendar.name + "' ist readOnly – Skip.");
            return;
        }

        final ContentResolver resolver = mContext.getContentResolver();

        final String[] PROJECTION = new String[] {
                CalendarContract.Events._ID,
                CalendarContract.Events._SYNC_ID,
                CalendarContract.Events.SYNC_DATA2, // eventId
                CalendarContract.Events.TITLE,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.ALL_DAY
        };

        final String selection = CalendarContract.Events.CALENDAR_ID + "=? AND " +
                CalendarContract.Events.DIRTY + "=1 AND " +
                CalendarContract.Events.DELETED + "=0 AND " +
                CalendarContract.Events._SYNC_ID + " IS NOT NULL";

        Cursor c = null;
        try {
            c = resolver.query(CalendarContract.Events.CONTENT_URI,
                    PROJECTION,
                    selection,
                    new String[] { String.valueOf(localCalendarId) },
                    null);

            if (c == null)
                return;

            while (c.moveToNext()) {
                long localEventId = c.getLong(0);
                String occurrenceId = c.getString(1);
                String remoteEventId = c.getString(2);

                String title = c.getString(3);
                String location = c.getString(4);
                String description = c.getString(5);
                long dtStart = c.getLong(6);
                long dtEnd = c.getLong(7);
                boolean allDay = c.getInt(8) == 1;

                if (TextUtils.isEmpty(occurrenceId))
                    continue;

                // Reparatur: Wenn _SYNC_ID kein Kerio occurrenceId ist, versuche ihn zu
                // resolven
                if (!isLikelyKerioOccurrenceId(occurrenceId) && !TextUtils.isEmpty(remoteEventId) && dtStart > 0) {
                    try {
                        String resolved = client.resolveOccurrenceIdForEvent(remoteCalendar.id, remoteEventId, dtStart);
                        if (!TextUtils.isEmpty(resolved)) {
                            occurrenceId = resolved;

                            ContentValues fix = new ContentValues();
                            fix.put(CalendarContract.Events._SYNC_ID, resolved);
                            resolver.update(buildSyncAdapterEventsUri(account, localEventId), fix, null, null);

                            Log.i(TAG, "UPDATE Repair: localId=" + localEventId + " -> occurrenceId=" + resolved);
                        }
                    } catch (Exception ex) {
                        Log.w(TAG, "UPDATE Repair resolveOccurrenceIdForEvent fehlgeschlagen: localId=" + localEventId
                                + " " + ex.getMessage(), ex);
                    }
                }

                if (!isLikelyKerioOccurrenceId(occurrenceId)) {
                    Log.w(TAG,
                            "pushLocalUpdatedEvents: _SYNC_ID ist keine Occurrence-ID, UPDATE wird übersprungen: localId="
                                    + localEventId + ", _SYNC_ID=" + occurrenceId);
                    continue;
                }

                KerioApiClient.RemoteEvent ev = new KerioApiClient.RemoteEvent();
                ev.summary = title;
                ev.location = location;
                ev.description = description;
                ev.dtStartUtcMillis = dtStart;
                ev.dtEndUtcMillis = dtEnd;
                ev.allDay = allDay;

                try {
                    client.updateOccurrence(occurrenceId, ev);

                    ContentValues cv = new ContentValues();
                    cv.put(CalendarContract.Events.DIRTY, 0);

                    int rows = resolver.update(
                            buildSyncAdapterEventsUri(account, localEventId),
                            cv,
                            null,
                            null);

                    Log.i(TAG, "Event aktualisiert (push->server): occurrenceId=" + occurrenceId +
                            ", localId=" + localEventId + ", rows=" + rows + ", title=" + title);

                    syncResult.stats.numUpdates++;

                } catch (Exception ex) {
                    Log.e(TAG, "pushLocalUpdatedEvents: UPDATE fehlgeschlagen für localId=" + localEventId +
                            ", occurrenceId=" + occurrenceId, ex);
                    syncResult.stats.numIoExceptions++;
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "pushLocalUpdatedEvents: Fehler beim lokalen Query", e);
            syncResult.stats.numIoExceptions++;
        } finally {
            if (c != null)
                c.close();
        }
    }

    // ------------------------------------------------------------------------
    // Kalender-Sync (lokale <-> Remote-Kalender)
    // ------------------------------------------------------------------------

    /**
     * @brief Synchronisiert Kalender für einen Account: Legt neue lokale
     *        Kalender an,
     *        aktualisiert bestehende und cached lokale Einstellungen.
     * @param account         Kerio-Account
     * @param remoteCalendars Liste Remote-Kalender vom Server
     * @param syncResult      Sync-Statistiken
     * @return Map RemoteCalendar.id -> lokale CalendarContract.Calendars._ID
     */
    private Map<String, Long> syncCalendarsForAccount(
            Account account,
            List<KerioApiClient.RemoteCalendar> remoteCalendars,
            SyncResult syncResult) {

        Map<String, Long> localByRemoteId = new HashMap<>();

        Set<String> remoteIds = new HashSet<>();
        for (KerioApiClient.RemoteCalendar rc : remoteCalendars) {
            if (rc.id != null) {
                remoteIds.add(rc.id);
            }
        }

        Uri calendarsUri = CalendarContract.Calendars.CONTENT_URI;

        String[] projection = new String[] {
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars._SYNC_ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.VISIBLE,
                CalendarContract.Calendars.SYNC_EVENTS,
                CalendarContract.Calendars.CALENDAR_COLOR
        };

        String selection = CalendarContract.Calendars.ACCOUNT_NAME + "=? AND " +
                CalendarContract.Calendars.ACCOUNT_TYPE + "=?";
        String[] selectionArgs = new String[] { account.name, account.type };

        Cursor cursor = mContentResolver.query(calendarsUri, projection, selection, selectionArgs, null);

        Map<String, Long> remoteIdToLocalId = new HashMap<>();
        Map<String, LocalCalendarInfo> localInfoByRemoteId = new HashMap<>();
        Map<Long, String> localCalendarRemoteId = new HashMap<>();

        if (cursor != null) {
            try {
                while (cursor.moveToNext()) {
                    long localId = cursor.getLong(0);
                    String syncId = cursor.getString(1);
                    String name = cursor.getString(2);
                    int visible = cursor.getInt(3);
                    int syncEvents = cursor.getInt(4);
                    Integer calendarColor = cursor.isNull(5) ? null : cursor.getInt(5);

                    if (syncId != null && !syncId.isEmpty()) {
                        remoteIdToLocalId.put(syncId, localId);
                        localCalendarRemoteId.put(localId, syncId);

                        LocalCalendarInfo info = new LocalCalendarInfo();
                        info.localId = localId;
                        info.remoteId = syncId;
                        info.visible = visible != 0;
                        info.syncEvents = syncEvents != 0;
                        info.calendarColor = calendarColor;
                        localInfoByRemoteId.put(syncId, info);

                        Log.d(TAG, "Lokaler Kalender gefunden: id=" + localId +
                                ", _SYNC_ID=" + syncId +
                                ", name=" + name +
                                ", visible=" + visible +
                                ", syncEvents=" + syncEvents +
                                ", calendarColor=" + calendarColor);
                    }
                }
            } finally {
                cursor.close();
            }
        }

        mLocalCalendarInfoByRemoteId = localInfoByRemoteId;
        Uri syncCalendarsUri = calendarsUri.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type)
                .build();

        for (KerioApiClient.RemoteCalendar rc : remoteCalendars) {
            if (rc.id == null || rc.id.isEmpty()) {
                continue;
            }

            Long localId = remoteIdToLocalId.get(rc.id);

            ContentValues values = new ContentValues();
            values.put(CalendarContract.Calendars.ACCOUNT_NAME, account.name);
            values.put(CalendarContract.Calendars.ACCOUNT_TYPE, account.type);

            String displayName = (rc.displayName != null && !rc.displayName.isEmpty()) ? rc.displayName : rc.name;

            values.put(CalendarContract.Calendars.NAME, displayName);
            values.put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, displayName);
            values.put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                    rc.readOnly
                            ? CalendarContract.Calendars.CAL_ACCESS_READ
                            : CalendarContract.Calendars.CAL_ACCESS_OWNER);

            values.put(CalendarContract.Calendars.OWNER_ACCOUNT, account.name);

            values.put(CalendarContract.Calendars._SYNC_ID, rc.id);

            if (localId == null) {
                // Defaults nur beim ersten Anlegen setzen – Nutzer-Änderungen
                // (Farbe/Sichtbarkeit/Sync) dürfen nicht überschrieben werden.
                values.put(CalendarContract.Calendars.VISIBLE, 1);
                values.put(CalendarContract.Calendars.SYNC_EVENTS, 1);
                // Keine feste Standardfarbe erzwingen – Android wählt eine; Nutzer kann sie
                // später ändern.
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
                localByRemoteId.put(rc.id, localId);
                Log.i(TAG, "Lokaler Kalender aktualisiert: remoteId=" + rc.id +
                        ", localId=" + localId + ", rows=" + rows);
                syncResult.stats.numUpdates += rows;
            }
        }

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

    /**
     * @brief Synchronisiert Events für einen lokalen Kalender: Fügt neue Events
     *        hinzu,
     *        aktualisiert bestehende.
     * @param account         Kerio-Account
     * @param localCalendarId Lokale Kalender-ID
     * @param remoteEvents    Liste Remote-Events vom Server
     * @param syncResult      Sync-Statistiken
     */
    private void syncEventsForCalendar(Account account,
            long localCalendarId,
            List<KerioApiClient.RemoteEvent> remoteEvents,
            SyncResult syncResult) {

        Set<String> remoteUids = new HashSet<>();
        for (KerioApiClient.RemoteEvent re : remoteEvents) {
            if (re.uid == null || re.uid.isEmpty()) {
                Log.w(TAG, "RemoteEvent ohne UID – wird ignoriert: " + re);
                continue;
            }
            remoteUids.add(re.uid);
        }

        Uri eventsUri = CalendarContract.Events.CONTENT_URI;

        String[] projection = new String[] {
                CalendarContract.Events._ID,
                CalendarContract.Events._SYNC_ID,
                CalendarContract.Events.SYNC_DATA1, // lastModifiedUtcMillis
                CalendarContract.Events.DELETED,
                CalendarContract.Events.TITLE
        };

        String selection = CalendarContract.Events.CALENDAR_ID + " = ?";
        String[] selectionArgs = new String[] { String.valueOf(localCalendarId) };

        Cursor cursor = mContentResolver.query(
                eventsUri,
                projection,
                selection,
                selectionArgs,
                null);

        Map<String, LocalEventInfo> localByUid = new HashMap<>();

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
                    info.lastModifiedUtcMillis = lastModifiedLocal;
                    info.deleted = (deleted != 0);
                    info.title = title;

                    if (syncId != null && !syncId.isEmpty()) {
                        localByUid.put(syncId, info);
                    }
                }
            } finally {
                cursor.close();
            }
        }

        Uri syncEventsUri = buildSyncAdapterEventsUri(account);

        // Remote -> lokal (Insert/Update)
        for (KerioApiClient.RemoteEvent remote : remoteEvents) {
            if (remote.uid == null || remote.uid.isEmpty())
                continue;

            LocalEventInfo local = localByUid.get(remote.uid);

            long remoteLastMod = (remote.lastModifiedUtcMillis > 0L)
                    ? remote.lastModifiedUtcMillis
                    : remote.lastModifiedUtc;

            ContentValues values = new ContentValues();
            values.put(CalendarContract.Events.CALENDAR_ID, localCalendarId);
            values.put(CalendarContract.Events.TITLE, remote.summary);
            values.put(CalendarContract.Events.DESCRIPTION, remote.description);
            values.put(CalendarContract.Events.EVENT_LOCATION, remote.location);
            values.put(CalendarContract.Events.DTSTART, remote.dtStartUtcMillis);
            values.put(CalendarContract.Events.DTEND, remote.dtEndUtcMillis);
            values.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC");
            values.put(CalendarContract.Events.ALL_DAY, remote.allDay ? 1 : 0);

            // Sync-Mapping
            values.put(CalendarContract.Events._SYNC_ID, remote.uid); // occurrenceId
            values.put(CalendarContract.Events.SYNC_DATA1, String.valueOf(remoteLastMod));
            values.put(CalendarContract.Events.SYNC_DATA2, remote.eventId); // eventId
            values.put(CalendarContract.Events.DIRTY, 0);

            if (local == null) {
                Uri inserted = mContentResolver.insert(syncEventsUri, values);
                if (inserted != null) {
                    long newId = ContentUris.parseId(inserted);
                    Log.i(TAG, "Neues lokales Event angelegt: uid=" + remote.uid +
                            ", localId=" + newId + ", title=" + remote.summary);
                    syncResult.stats.numInserts++;
                }
            } else {
                boolean needsUpdate;
                if (remoteLastMod > 0 && remoteLastMod > local.lastModifiedUtcMillis) {
                    needsUpdate = true;
                } else if (remoteLastMod == 0L) {
                    needsUpdate = true;
                } else {
                    needsUpdate = false;
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
            if (local.uid == null || local.uid.isEmpty())
                continue;

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

    /*
     * @brief Hilfsmethode: Prüft, ob die übergebene Zeichenkette
     * wahrscheinlich eine Kerio-Occurrence-ID ist.
     * 
     * @param occurrenceId Zu prüfende Occurrence-ID
     * 
     * @return true, wenn es sich wahrscheinlich um eine Kerio-Occurrence-ID
     * handelt
     */
    private static class LocalEventInfo {
        long id;
        String uid;
        long lastModifiedUtcMillis;
        boolean deleted;
        String title;

        @Override
        public String toString() {
            return "LocalEventInfo{" +
                    "id=" + id +
                    ", uid='" + uid + '\'' +
                    ", lastModifiedUtcMillis=" + lastModifiedUtcMillis +
                    ", deleted=" + deleted +
                    ", title='" + title + '\'' +
                    '}';
        }
    }

    // ------------------------------------------------------------------------
    // SSL-Hilfsmethoden (Custom-CA)
    // ------------------------------------------------------------------------

    /*
     * @brief Erstellt eine SSLSocketFactory, die das Zertifikat aus der
     * angegebenen
     * URI als vertrauenswürdige CA verwendet.
     * 
     * @param context Kontext
     * 
     * @param caUri URI des CA-Zertifikats (content://-URI)
     * 
     * @return SSLSocketFactory mit Custom-CA
     * 
     * @throws Exception Bei Fehlern
     */
    private SSLSocketFactory createSslSocketFactoryForCaUri(Context context, Uri caUri) throws Exception {
        // Delegiere an zentrale Helper-Implementierung, die eine TrustManagerFactory
        // mit dem bereitgestellten KeyStore initialisiert (korrekte
        // Zertifikatsprüfung).
        return KerioSslHelper.loadCustomCaSocketFactory(context, caUri);
    }

    /*
     * @brief Unterdrückt Change-Trigger (ContentObserver/SyncAdapter) für
     * bestimmte Zeit.
     * 
     * @param reason Grund für die Unterdrückung (Debug-Info)
     * 
     * @param seconds Dauer der Unterdrückung in Sekunden
     */
    private void suppressChangeTriggers(String reason, int seconds) {
        try {
            long now = System.currentTimeMillis();
            long until = now + (Math.max(0, seconds) * 1000L);

            SharedPreferences prefs = mContext.getSharedPreferences(KerioAccountConstants.PREFS_NAME,
                    Context.MODE_PRIVATE);
            prefs.edit()
                    .putLong(KerioAccountConstants.PREF_SUPPRESS_CHANGE_TRIGGERS_UNTIL_MS, until)
                    .putString(KerioAccountConstants.PREF_SUPPRESS_REASON, reason)
                    .apply();

            Log.i(TAG, "Change-Trigger unterdrückt: until=" + until + " (" + seconds + "s), reason=" + reason);
        } catch (Exception e) {
            Log.w(TAG, "suppressChangeTriggers() Fehler: " + e.getMessage(), e);
        }
    }
}