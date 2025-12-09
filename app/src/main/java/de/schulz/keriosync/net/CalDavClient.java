package de.schulz.keriosync.net;

public class CalDavClient {

    private final String mServerUrl;
    private final String mUsername;
    private final String mPassword;

    public CalDavClient(String serverUrl, String username, String password) {
        this.mServerUrl = serverUrl;
        this.mUsername = username;
        this.mPassword = password;
    }

    // TODO: Implementiere hier:
    // - Discovery der CalDAV-Collections (PROPFIND auf /caldav/)
    // - Laden der Events aus einer Collection (REPORT)
    // - Erstellen/Aktualisieren/Löschen von Events (PUT/DELETE)

    // Beispiel-Methoden-Signaturen:

    // public List<RemoteCalendar> fetchCalendars() { ... }
    // public List<RemoteEvent> fetchEvents(RemoteCalendar calendar) { ... }
    // public void upsertEvent(RemoteCalendar calendar, RemoteEvent event) { ... }
    // public void deleteEvent(RemoteCalendar calendar, String uid) { ... }
}
