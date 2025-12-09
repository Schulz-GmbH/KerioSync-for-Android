package de.schulz.keriosync.sync;

import android.accounts.Account;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SyncResult;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.util.Log;

import de.schulz.keriosync.net.CalDavClient;

public class KerioCalendarSyncAdapter extends AbstractThreadedSyncAdapter {

    private static final String TAG = "KerioCalendarSync";
    private final ContentResolver mContentResolver;

    public KerioCalendarSyncAdapter(Context context, boolean autoInitialize) {
        super(context, autoInitialize);
        mContentResolver = context.getContentResolver();
    }

    @Override
    public void onPerformSync(Account account,
                              Bundle extras,
                              String authority,
                              ContentProviderClient provider,
                              SyncResult syncResult) {

        Log.i(TAG, "Starte Kalender-Sync für Account: " + account.name);

        try {
            // 1. Account-Daten auslesen (Server-URL, Benutzername, Passwort)
            //    z.B. aus AccountManager-Userdata
            //    (hier nur angedeutet)
            // AccountManager am = AccountManager.get(getContext());
            // String serverUrl = am.getUserData(account, "server_url");
            // String username = account.name;
            // String password = am.getPassword(account);

            String serverUrl = "https://dein-kerio-server.example.com";
            String username = account.name;
            String password = "TODO_HOLE_PASSWORT";

            CalDavClient client = new CalDavClient(serverUrl, username, password);

            // 2. Kalender-Collections von Kerio holen (inkl. öffentliche + freigegebene)
            //    TODO: Implementiere fetchCalendars() in CalDavClient
            // List<RemoteCalendar> remoteCalendars = client.fetchCalendars();

            // 3. Lokale Kalender (CalendarContract.Calendars) laden, die zu diesem Account gehören
            Uri calendarsUri = CalendarContract.Calendars.CONTENT_URI;
            String selection = CalendarContract.Calendars.ACCOUNT_NAME + " = ? AND " +
                    CalendarContract.Calendars.ACCOUNT_TYPE + " = ?";
            String[] selectionArgs = new String[]{account.name, account.type};

            Cursor cursor = mContentResolver.query(calendarsUri, null, selection, selectionArgs, null);

            // TODO: Mapping Logik:
            // - remoteCalendars mit lokalen Kalendern abgleichen
            // - fehlende Kalender anlegen
            // - nicht mehr vorhandene ggf. deaktivieren

            if (cursor != null) {
                cursor.close();
            }

            // 4. Für jeden Kalender: Events synchronisieren
            // - Events vom Server holen
            // - über CalendarContract.Events eintragen/aktualisieren/löschen
            // TODO: Implementiere Event-Sync

            Log.i(TAG, "Kalender-Sync erfolgreich abgeschlossen");

        } catch (Exception e) {
            Log.e(TAG, "Fehler beim Kalender-Sync", e);
            syncResult.hasError();
        }
    }
}
