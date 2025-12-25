package de.schulz.keriosync.net;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;

import java.nio.charset.StandardCharsets;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * JSON-RPC Client für Kerio Connect (Client-API/Webmail).
 *
 * Enthält:
 * - Session.login / Session.logout
 * - Folders.get (Kalenderordner)
 * - Occurrences.get (Events Pull)
 *
 * Erweiterung:
 * - Events.create (Events Push: lokale neu erstellte Termine zum Server)
 *
 * Fix/Erweiterung:
 * - Occurrences.getById Parsing korrigiert
 * - Helper zum Resolven einer Occurrence-ID für neu erstellte Events
 * (Events.create liefert i.d.R. Event-ID)
 */
public class KerioApiClient {

    private static final String TAG = "KerioApiClient";

    private static final String JSON_RPC_VERSION = "2.0";

    private static final String APP_NAME = "KerioSync Android";
    private static final String APP_VENDOR = "Schulz GmbH";
    private static final String APP_VERSION = "1.0";

    /**
     * Default-Sync-Zeitraum, wenn der SyncAdapter keine explizite Spanne vorgibt.
     */
    private static final long DEFAULT_PAST_WINDOW_MS = 180L * 24L * 60L * 60L * 1000L; // 180 Tage zurück
    private static final long DEFAULT_FUTURE_WINDOW_MS = 365L * 24L * 60L * 60L * 1000L; // 365 Tage vor

    /**
     * Kerio DateTime Strings (RFC2445-Style):
     * - UTC: 20251210T110355Z
     * - Offset: 20251210T120000+0100
     * - ggf.: 20251210T120000+01:00
     */
    private static final DateTimeFormatter KERIO_UTC_Z_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'",
            Locale.US);

    private static final DateTimeFormatter KERIO_OFFSET_NO_COLON_FORMAT = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmssZ", Locale.US); // +0100

    private static final DateTimeFormatter KERIO_OFFSET_COLON_FORMAT = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmssXXX", Locale.US); // +01:00

    /**
     * Kerio Date-only Format (Ganztags-/Mehrtagestermine)
     * Beispiel: 20251217
     */
    private static final DateTimeFormatter KERIO_DATE_ONLY_FORMAT = DateTimeFormatter.BASIC_ISO_DATE
            .withLocale(Locale.US);
    /**
     * Vollständige JSON-RPC-Endpoint-URL, z. B.
     * https://host/webmail/api/jsonrpc/
     */
    private final String mApiUrl;

    private final String mUsername;
    private final String mPassword;

    private String mToken;

    private final Map<String, String> mCookies = new HashMap<>();

    private final AtomicLong mRequestId = new AtomicLong(1L);

    private final boolean mTrustAllCerts;
    private final SSLSocketFactory mCustomSslSocketFactory;

    private static SSLSocketFactory sUnsafeSslSocketFactory;
    private static HostnameVerifier sUnsafeHostnameVerifier;

    // ------------------------------------------------------------------------
    // Konstruktoren
    // ------------------------------------------------------------------------

    public KerioApiClient(String apiUrl, String username, String password) {
        this(apiUrl, username, password, false, null);
    }

    public KerioApiClient(String apiUrl,
            String username,
            String password,
            boolean trustAllCerts,
            SSLSocketFactory customSslSocketFactory) {

        String normalized = normalizeKerioApiUrl(apiUrl);
        Log.d(TAG, "KerioApiClient: Original-URL='" + apiUrl + "', normalisierte API-URL='" + normalized + "'");

        this.mApiUrl = normalized;
        this.mUsername = username;
        this.mPassword = password;
        this.mTrustAllCerts = trustAllCerts;
        this.mCustomSslSocketFactory = customSslSocketFactory;
    }

    /**
     * Normalisiert die vom Benutzer eingegebene URL zu einer
     * Kerio-JSON-RPC-Endpoint-URL.
     */
    private static String normalizeKerioApiUrl(String apiUrl) {
        if (apiUrl == null) {
            return null;
        }

        apiUrl = apiUrl.trim();

        if (!apiUrl.startsWith("http://") && !apiUrl.startsWith("https://")) {
            apiUrl = "https://" + apiUrl;
        }

        while (apiUrl.endsWith("/")) {
            apiUrl = apiUrl.substring(0, apiUrl.length() - 1);
        }

        String lower = apiUrl.toLowerCase(Locale.ROOT);
        if (!lower.contains("jsonrpc")) {
            apiUrl = apiUrl + "/webmail/api/jsonrpc/";
        }

        return apiUrl;
    }

    // ------------------------------------------------------------------------
    // Login / Logout
    // ------------------------------------------------------------------------

    public synchronized void login() throws IOException, JSONException {
        JSONObject params = new JSONObject();
        params.put("userName", mUsername);
        params.put("password", mPassword);

        JSONObject application = new JSONObject();
        application.put("name", APP_NAME);
        application.put("vendor", APP_VENDOR);
        application.put("version", APP_VERSION);

        params.put("application", application);

        Log.d(TAG, "Starte Session.login gegen " + mApiUrl + " für Benutzer " + mUsername);

        JSONObject response = call("Session.login", params, true);

        if (response.has("result")) {
            JSONObject result = response.getJSONObject("result");
            if (result.has("token")) {
                mToken = result.getString("token");
                Log.d(TAG, "Session.login erfolgreich, Token erhalten.");
            } else {
                Log.w(TAG, "Session.login Antwort enthält kein Token-Feld.");
            }
        } else {
            Log.w(TAG, "Session.login Antwort enthält kein result-Feld.");
        }

        if (mToken == null) {
            throw new IOException("Kerio Session.login Antwort enthält kein Token.");
        }
    }

    public synchronized void logout() throws IOException, JSONException {
        try {
            call("Session.logout", new JSONObject(), false);
        } finally {
            mToken = null;
            mCookies.clear();
        }
    }

    private void ensureLoggedIn() throws IOException, JSONException {
        if (mToken == null) {
            login();
        }
    }

    // ------------------------------------------------------------------------
    // DTOs
    // ------------------------------------------------------------------------

    public static class RemoteCalendar {
        public String id;
        public String name;

        /**
         * Optional: Name, der in Android als Kalender-DisplayName genutzt werden kann
         * (z.B. "Public: Team", "Max: Urlaub").
         */
        public String displayName;

        /** Owner (bei Shared/Delegation häufig der Postfach-/Benutzername). */
        public String owner;

        /** Owner-E-Mail, falls vom Server geliefert. */
        public String ownerEmail;

        /**
         * placeType laut Kerio (z.B. FPlaceMailbox, FPlacePeople, FPlacePublic,
         * FPlaceResources, ...).
         */
        public String placeType;

        /** True, wenn dieser Kalender aus Public Folders stammt. */
        public boolean isPublic;

        /** True, wenn dieser Kalender aus Shared Folders stammt. */
        public boolean isShared;

        /** True, wenn dieser Kalender delegiert ist (on behalf of). */
        public boolean isDelegated;

        public boolean readOnly;
        public String color;

        @Override
        public String toString() {
            return "RemoteCalendar{" +
                    "id='" + id + '\'' +
                    ", name='" + name + '\'' +
                    ", displayName='" + displayName + '\'' +
                    ", owner='" + owner + '\'' +
                    ", ownerEmail='" + ownerEmail + '\'' +
                    ", placeType='" + placeType + '\'' +
                    ", isPublic=" + isPublic +
                    ", isShared=" + isShared +
                    ", isDelegated=" + isDelegated +
                    ", readOnly=" + readOnly +
                    ", color='" + color + '\'' +
                    '}';
        }
    }

    public static class RemoteEvent {
        public String uid; // Occurrence-ID (id aus Occurrences.get)
        public String eventId; // Event-ID (eventId aus Occurrences.get)
        public String calendarId;

        public String summary;
        public String description;
        public String location;

        public long dtStartUtcMillis;
        public long dtEndUtcMillis;
        public boolean allDay;

        public long lastModifiedUtcMillis;
        public long lastModifiedUtc;

        @Override
        public String toString() {
            return "RemoteEvent{" +
                    "uid='" + uid + '\'' +
                    ", eventId='" + eventId + '\'' +
                    ", calendarId='" + calendarId + '\'' +
                    ", summary='" + summary + '\'' +
                    ", dtStartUtcMillis=" + dtStartUtcMillis +
                    ", dtEndUtcMillis=" + dtEndUtcMillis +
                    ", allDay=" + allDay +
                    ", lastModifiedUtcMillis=" + lastModifiedUtcMillis +
                    ", lastModifiedUtc=" + lastModifiedUtc +
                    '}';
        }
    }

    /**
     * Ergebnis für Events.create
     */
    public static class CreateResult {
        public int inputIndex;
        public String id; // meist Event-ID

        @Override
        public String toString() {
            return "CreateResult{" +
                    "inputIndex=" + inputIndex +
                    ", id='" + id + '\'' +
                    '}';
        }
    }

    // ------------------------------------------------------------------------
    // Kalender / Events
    // ------------------------------------------------------------------------
    public List<RemoteCalendar> fetchCalendars() throws IOException, JSONException {
        ensureLoggedIn();

        // Feature: Zusätzlich zu den eigenen Kalendern werden auch Public sowie
        // Shared/Delegated Kalender
        // eingebunden (soweit die Server-Version die Methoden bereitstellt).
        //
        // 1) Eigene Kalender: Folders.get
        // 2) Public Kalender: Folders.getPublic (optional)
        // 3) Shared/Delegated: Folders.getSharedMailboxList (optional)
        // Optional Filter: Folders.getSubscribed (wenn vorhanden), um nur
        // "eingeblendete" Kalender zu übernehmen.

        Map<String, RemoteCalendar> byId = new HashMap<>();

        // (1) Eigene Kalender
        JSONObject resp = call("Folders.get", new JSONObject(), true);
        addCalendarsFromFolderResult(resp, byId, false, false, null, null);

        // (2) Public Kalender (optional)
        try {
            JSONObject publicResp = call("Folders.getPublic", new JSONObject(), true);
            addCalendarsFromFolderResult(publicResp, byId, true, false, null, null);
        } catch (Exception e) {
            Log.i(TAG, "Folders.getPublic nicht verfügbar/fehlgeschlagen: " + e.getMessage());
        }

        // (3) Subscribed IDs (optional)
        List<String> subscribedFolderIds = new ArrayList<>();
        try {
            JSONObject subsResp = call("Folders.getSubscribed", new JSONObject(), true);
            subscribedFolderIds = extractSubscribedFolderIds(subsResp);
        } catch (Exception e) {
            Log.i(TAG, "Folders.getSubscribed nicht verfügbar/fehlgeschlagen: " + e.getMessage());
        }

        // (4) Shared/Delegated (optional)
        try {
            JSONObject sharedResp = call("Folders.getSharedMailboxList", new JSONObject(), true);
            addCalendarsFromSharedMailboxListResult(sharedResp, byId, subscribedFolderIds);
        } catch (Exception e) {
            Log.i(TAG, "Folders.getSharedMailboxList nicht verfügbar/fehlgeschlagen: " + e.getMessage());
        }

        return new ArrayList<>(byId.values());
    }

    private void addCalendarsFromFolderResult(JSONObject resp,
            Map<String, RemoteCalendar> byId,
            boolean isPublic,
            boolean isShared,
            String forcedOwnerName,
            String forcedOwnerEmail) throws JSONException {

        if (resp == null || !resp.has("result")) {
            return;
        }

        JSONObject result = resp.getJSONObject("result");
        JSONArray folderList = result.optJSONArray("list");
        if (folderList == null) {
            return;
        }

        for (int i = 0; i < folderList.length(); i++) {
            JSONObject folder = folderList.optJSONObject(i);
            if (folder == null) {
                continue;
            }

            RemoteCalendar rc = folderToRemoteCalendar(folder, isPublic, isShared, forcedOwnerName, forcedOwnerEmail);
            if (rc == null || rc.id == null || rc.id.isEmpty()) {
                continue;
            }

            RemoteCalendar existing = byId.get(rc.id);
            if (existing == null) {
                byId.put(rc.id, rc);
            } else {
                mergeCalendar(existing, rc);
            }
        }
    }

    private void addCalendarsFromSharedMailboxListResult(JSONObject resp,
            Map<String, RemoteCalendar> byId,
            List<String> subscribedFolderIds) throws JSONException {

        if (resp == null || !resp.has("result")) {
            return;
        }

        JSONObject result = resp.getJSONObject("result");

        // Kerio-Versionen variieren: "mailboxes" oder "list"
        JSONArray mailboxes = result.optJSONArray("mailboxes");
        if (mailboxes == null) {
            mailboxes = result.optJSONArray("list");
        }
        if (mailboxes == null) {
            return;
        }

        for (int i = 0; i < mailboxes.length(); i++) {
            JSONObject mb = mailboxes.optJSONObject(i);
            if (mb == null) {
                continue;
            }

            String mailboxOwnerName = null;
            String mailboxOwnerEmail = null;

            JSONObject principal = mb.optJSONObject("principal");
            if (principal != null) {
                mailboxOwnerName = principal.optString("name", null);
                mailboxOwnerEmail = principal.optString("emailAddress", null);
                if (mailboxOwnerEmail == null || mailboxOwnerEmail.isEmpty()) {
                    mailboxOwnerEmail = principal.optString("email", null);
                }
            }

            JSONArray folders = mb.optJSONArray("folders");
            if (folders == null) {
                continue;
            }

            for (int f = 0; f < folders.length(); f++) {
                JSONObject folder = folders.optJSONObject(f);
                if (folder == null) {
                    continue;
                }

                RemoteCalendar rc = folderToRemoteCalendar(folder, false, true, mailboxOwnerName, mailboxOwnerEmail);
                if (rc == null || rc.id == null || rc.id.isEmpty()) {
                    continue;
                }

                // Optional: Wenn subscribedFolderIds verfügbar ist, übernehmen wir bevorzugt
                // nur die eingeblendeten.
                if (subscribedFolderIds != null && !subscribedFolderIds.isEmpty()) {
                    boolean checked = folder.optBoolean("checked", false);
                    boolean subscribed = subscribedFolderIds.contains(rc.id);
                    if (!checked && !subscribed) {
                        continue;
                    }
                }

                RemoteCalendar existing = byId.get(rc.id);
                if (existing == null) {
                    byId.put(rc.id, rc);
                } else {
                    mergeCalendar(existing, rc);
                }
            }
        }
    }

    private List<String> extractSubscribedFolderIds(JSONObject resp) throws JSONException {
        List<String> ids = new ArrayList<>();

        if (resp == null || !resp.has("result")) {
            return ids;
        }

        JSONObject result = resp.getJSONObject("result");

        // Erwartung (je nach Version): result.list[].subscribedFolderIds[]
        JSONArray list = result.optJSONArray("list");
        if (list == null) {
            list = result.optJSONArray("mailboxes");
        }
        if (list == null) {
            return ids;
        }

        for (int i = 0; i < list.length(); i++) {
            JSONObject mb = list.optJSONObject(i);
            if (mb == null) {
                continue;
            }

            JSONArray subscribed = mb.optJSONArray("subscribedFolderIds");
            if (subscribed == null) {
                continue;
            }

            for (int s = 0; s < subscribed.length(); s++) {
                String id = subscribed.optString(s, null);
                if (id != null && !id.isEmpty() && !ids.contains(id)) {
                    ids.add(id);
                }
            }
        }

        return ids;
    }

    private RemoteCalendar folderToRemoteCalendar(JSONObject folder,
            boolean isPublic,
            boolean isShared,
            String forcedOwnerName,
            String forcedOwnerEmail) {

        if (folder == null) {
            return null;
        }

        String type = folder.optString("type", "");
        if (!"FCalendar".equals(type)) {
            return null;
        }

        RemoteCalendar rc = new RemoteCalendar();

        rc.id = folder.optString("id", null);
        if (rc.id == null || rc.id.isEmpty()) {
            return null;
        }

        rc.name = folder.optString("name", rc.id);

        rc.placeType = folder.optString("placeType", null);
        rc.isPublic = isPublic || "FPlacePublic".equals(rc.placeType);
        rc.isShared = isShared || "FPlacePeople".equals(rc.placeType);
        rc.isDelegated = folder.optBoolean("isDelegated", false);

        String ownerFromFolder = folder.optString("ownerName",
                folder.optString("owner", ""));
        if (ownerFromFolder == null || ownerFromFolder.isEmpty()) {
            ownerFromFolder = forcedOwnerName;
        }
        if (ownerFromFolder == null || ownerFromFolder.isEmpty()) {
            ownerFromFolder = mUsername;
        }
        rc.owner = ownerFromFolder;

        String ownerEmail = folder.optString("emailAddress", null);
        if (ownerEmail == null || ownerEmail.isEmpty()) {
            ownerEmail = forcedOwnerEmail;
        }
        rc.ownerEmail = ownerEmail;

        boolean readOnly = false;
        JSONObject rights = folder.optJSONObject("rights");
        if (rights != null) {
            boolean canModify = rights.optBoolean("modify", false)
                    || rights.optBoolean("modifyItems", false)
                    || rights.optBoolean("full", false)
                    || rights.optBoolean("owner", false);

            readOnly = !canModify;
        }
        rc.readOnly = readOnly;

        rc.color = folder.optString("color", null);

        // DisplayName: Shared/Public in Android besser erkennbar machen
        if (rc.isPublic) {
            rc.displayName = "Public: " + rc.name;
        } else if (rc.isShared || rc.isDelegated) {
            rc.displayName = rc.owner + ": " + rc.name;
        } else {
            rc.displayName = rc.name;
        }

        return rc;
    }

    private void mergeCalendar(RemoteCalendar base, RemoteCalendar incoming) {
        if (base == null || incoming == null) {
            return;
        }

        if ((base.name == null || base.name.isEmpty()) && incoming.name != null) {
            base.name = incoming.name;
        }
        if ((base.displayName == null || base.displayName.isEmpty()) && incoming.displayName != null) {
            base.displayName = incoming.displayName;
        }
        if ((base.owner == null || base.owner.isEmpty()) && incoming.owner != null) {
            base.owner = incoming.owner;
        }
        if ((base.ownerEmail == null || base.ownerEmail.isEmpty()) && incoming.ownerEmail != null) {
            base.ownerEmail = incoming.ownerEmail;
        }
        if ((base.placeType == null || base.placeType.isEmpty()) && incoming.placeType != null) {
            base.placeType = incoming.placeType;
        }

        base.isPublic = base.isPublic || incoming.isPublic;
        base.isShared = base.isShared || incoming.isShared;
        base.isDelegated = base.isDelegated || incoming.isDelegated;

        // Wenn irgendeine Quelle Schreibrechte meldet, ist es nicht readOnly.
        base.readOnly = base.readOnly && incoming.readOnly;

        if ((base.color == null || base.color.isEmpty()) && incoming.color != null) {
            base.color = incoming.color;
        }
    }

    private void parseEventsResponse(JSONObject response,
            RemoteCalendar calendar,
            List<RemoteEvent> out)
            throws JSONException {

        if (response == null) {
            Log.w(TAG, "parseEventsResponse(): response == null");
            return;
        }

        JSONObject result = response.optJSONObject("result");
        if (result == null) {
            Log.w(TAG, "parseEventsResponse(): result-Objekt fehlt in JSON");
            return;
        }

        JSONArray list = result.optJSONArray("list");
        if (list == null) {
            list = result.optJSONArray("occurrences");
        }

        if (list == null) {
            Log.i(TAG, "parseEventsResponse(): keine list/occurrences im Ergebnis");
            return;
        }

        for (int i = 0; i < list.length(); i++) {
            JSONObject occ = list.optJSONObject(i);
            if (occ == null)
                continue;

            RemoteEvent evt = new RemoteEvent();

            // Kerio Occurrence-ID (wichtig für Update/Delete)
            evt.uid = occ.optString("id", null);

            // Event-ID (für Zuordnung/Debug/Resolve)
            evt.eventId = occ.optString("eventId", null);

            evt.calendarId = calendar.id;

            evt.summary = occ.optString("summary", "");
            evt.description = occ.optString("description", "");
            evt.location = occ.optString("location", "");

            // <<< WICHTIG: allDay VOR Start/End bestimmen >>>
            evt.allDay = occ.optBoolean("isAllDay", false);

            String startStr = occ.optString("start", null);
            if (startStr != null && !startStr.isEmpty()) {
                if (evt.allDay && isKerioDateOnly(startStr)) {
                    evt.dtStartUtcMillis = parseKerioDateOnlyStartUtcMillis(startStr);
                } else {
                    evt.dtStartUtcMillis = parseKerioUtcDateTimeString(startStr);
                }
            }

            String endStr = occ.optString("end", null);
            if (endStr != null && !endStr.isEmpty()) {
                if (evt.allDay && isKerioDateOnly(endStr)) {
                    // Kerio liefert hier INKLUSIV -> Android braucht EXKLUSIV
                    evt.dtEndUtcMillis = parseKerioDateOnlyEndExclusiveUtcMillis(endStr);
                } else {
                    evt.dtEndUtcMillis = parseKerioUtcDateTimeString(endStr);
                }
            }

            String lmStr = occ.optString("lastModificationTime", null);
            if (lmStr != null && !lmStr.isEmpty()) {
                evt.lastModifiedUtcMillis = parseKerioUtcDateTimeString(lmStr);
                evt.lastModifiedUtc = evt.lastModifiedUtcMillis;
            } else {
                evt.lastModifiedUtcMillis = 0L;
                evt.lastModifiedUtc = 0L;
            }

            if (evt.dtEndUtcMillis <= 0 && evt.dtStartUtcMillis > 0) {
                evt.dtEndUtcMillis = evt.dtStartUtcMillis + (30L * 60L * 1000L);
            }

            if (evt.uid == null || evt.uid.isEmpty()) {
                if (evt.eventId != null && evt.dtStartUtcMillis > 0) {
                    evt.uid = evt.eventId + "@" + evt.dtStartUtcMillis;
                }
            }

            if (evt.uid != null && !evt.uid.isEmpty()) {
                out.add(evt);
            }
        }

        Log.i(TAG, "parseEventsResponse(): " + out.size() + " Events aus Occurrences.get gelesen.");
    }

    public List<RemoteEvent> fetchEvents(RemoteCalendar calendar, long sinceUtcMillis)
            throws IOException, JSONException {

        ensureLoggedIn();

        long now = System.currentTimeMillis();
        long start;
        long end;

        if (sinceUtcMillis > 0L) {
            start = sinceUtcMillis;
            end = now + DEFAULT_FUTURE_WINDOW_MS;
        } else {
            start = now - DEFAULT_PAST_WINDOW_MS;
            end = now + DEFAULT_FUTURE_WINDOW_MS;
        }

        return fetchEvents(mToken, calendar, start, end);
    }

    /**
     * Occurrences.get mit Zeitfenster.
     *
     * Kerio-Constraint:
     * - Condition 'end' nur mit 'LessThan'
     */
    public List<RemoteEvent> fetchEvents(String token,
            RemoteCalendar calendar,
            long startUtcMillis,
            long endUtcMillis)
            throws IOException, JSONException {

        List<RemoteEvent> events = new ArrayList<>();

        String windowStartStr = buildKerioUtcString(startUtcMillis);

        // Kerio verlangt end < X (LessThan), Grenze minimal exklusiv.
        String windowEndStr = buildKerioUtcString(endUtcMillis + 1000L);

        JSONObject query = new JSONObject();

        JSONArray fields = new JSONArray();
        fields.put("id");
        fields.put("eventId");
        fields.put("folderId");
        fields.put("summary");
        fields.put("description");
        fields.put("location");
        fields.put("start");
        fields.put("end");
        fields.put("lastModificationTime");
        fields.put("isAllDay");
        query.put("fields", fields);

        JSONArray conditions = new JSONArray();

        JSONObject condStartGe = new JSONObject();
        condStartGe.put("fieldName", "start");
        condStartGe.put("comparator", "GreaterEq");
        condStartGe.put("value", windowStartStr);
        conditions.put(condStartGe);

        JSONObject condEndLt = new JSONObject();
        condEndLt.put("fieldName", "end");
        condEndLt.put("comparator", "LessThan");
        condEndLt.put("value", windowEndStr);
        conditions.put(condEndLt);

        query.put("conditions", conditions);
        query.put("combining", "And");

        query.put("start", 0);
        query.put("limit", 1000);

        JSONArray orderBy = new JSONArray();
        JSONObject order = new JSONObject();
        order.put("columnName", "start");
        order.put("direction", "Asc");
        order.put("caseSensitive", false);
        orderBy.put(order);
        query.put("orderBy", orderBy);

        JSONObject params = new JSONObject();
        params.put("token", mToken);
        params.put("folderIds", new JSONArray().put(calendar.id));
        params.put("query", query);

        JSONObject response = call("Occurrences.get", params, true);

        parseEventsResponse(response, calendar, events);

        return events;
    }

    /**
     * Event auf dem Server anlegen (Events.create)
     *
     * Events.create liefert in der Praxis oft eine Event-ID zurück.
     * Für Update/Delete brauchst du aber die Occurrence-ID (Occurrences.*).
     */
    public CreateResult createEvent(RemoteCalendar calendar, RemoteEvent event) throws IOException, JSONException {
        ensureLoggedIn();

        if (calendar == null || calendar.id == null || calendar.id.isEmpty()) {
            throw new IllegalArgumentException("createEvent: calendar.id fehlt");
        }
        if (event == null) {
            throw new IllegalArgumentException("createEvent: event ist null");
        }
        if (event.dtStartUtcMillis <= 0L) {
            throw new IllegalArgumentException("createEvent: DTSTART fehlt/ungültig");
        }
        if (event.dtEndUtcMillis <= 0L) {
            event.dtEndUtcMillis = event.dtStartUtcMillis + (30L * 60L * 1000L);
        }

        JSONObject ev = new JSONObject();
        ev.put("folderId", calendar.id);
        ev.put("summary", event.summary != null ? event.summary : "");
        ev.put("location", event.location != null ? event.location : "");
        ev.put("description", event.description != null ? event.description : "");
        ev.put("isAllDay", event.allDay);

        if (event.allDay) {
            // Kerio erwartet bei All-Day häufig Date-only Werte (yyyyMMdd).
            // Android CalendarContract: DTSTART inklusiv, DTEND exklusiv.
            ev.put("start", formatKerioDateOnlyFromUtcMillis(event.dtStartUtcMillis));
            ev.put("end", formatKerioInclusiveEndDateOnlyFromAndroidExclusiveEnd(
                    event.dtEndUtcMillis,
                    event.dtStartUtcMillis));
        } else {
            ev.put("start", formatKerioUtcDateTime(event.dtStartUtcMillis));
            ev.put("end", formatKerioUtcDateTime(event.dtEndUtcMillis));
        }
        JSONArray events = new JSONArray();
        events.put(ev);

        JSONObject params = new JSONObject();
        params.put("token", mToken);
        params.put("events", events);

        JSONObject response = call("Events.create", params, true);

        JSONObject resultObj = response.optJSONObject("result");
        if (resultObj == null) {
            throw new IOException("Events.create: result ist NULL. Response=" + response);
        }

        JSONArray resArr = resultObj.optJSONArray("result");
        if (resArr == null || resArr.length() == 0) {
            throw new IOException("Events.create: result.result ist leer. Response=" + response);
        }

        JSONObject r0 = resArr.optJSONObject(0);
        if (r0 == null) {
            throw new IOException("Events.create: result[0] ist NULL. Response=" + response);
        }

        CreateResult cr = new CreateResult();
        cr.inputIndex = r0.optInt("inputIndex", 0);
        cr.id = r0.optString("id", null);

        if (cr.id == null || cr.id.isEmpty()) {
            throw new IOException("Events.create: id fehlt. Raw=" + response);
        }

        return cr;
    }

    // ---------------------------------------------------------------------
    // Update/Delete: Lokale Änderungen an bestehenden Terminen -> Kerio
    // ---------------------------------------------------------------------

    /**
     * Formatiert einen Unix-Timestamp (Millis) als Kerio-UtcDateTime im
     * UTC-Z-Format
     * (yyyyMMdd'T'HHmmss'Z').
     */
    public static String formatKerioUtcDateTime(long millis) {
        Instant instant = Instant.ofEpochMilli(millis);
        OffsetDateTime odt = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
        return odt.format(KERIO_UTC_Z_FORMAT);
    }

    /**
     * Formatiert ein UTC-Millis-Timestamp als Kerio Date-only String (yyyyMMdd).
     * Kerio liefert bei Ganztags-/Mehrtagesterminen häufig nur das Datum ohne
     * Uhrzeit.
     */
    public static String formatKerioUtcDateOnly(long millis) {
        Instant instant = Instant.ofEpochMilli(millis);
        LocalDate dateUtc = instant.atZone(ZoneOffset.UTC).toLocalDate();
        return dateUtc.format(KERIO_DATE_ONLY_FORMAT);
    }

    /**
     * Löscht eine Occurrence (ein Vorkommen) auf dem Kerio-Server.
     *
     * Kerio-API: Occurrences.remove(errors, occurrences)
     * Laut IDL sind nur 'id' und 'modification' erforderlich.
     */
    public void deleteOccurrence(String occurrenceId) throws IOException {
        try {
            ensureLoggedIn();
        } catch (JSONException e) {
            throw new IOException("deleteOccurrence: login/JSON Fehler: " + e.getMessage(), e);
        }

        if (occurrenceId == null || occurrenceId.trim().isEmpty()) {
            throw new IOException("deleteOccurrence: occurrenceId fehlt");
        }

        JSONObject occ = new JSONObject();
        try {
            occ.put("id", occurrenceId);
            occ.put("modification", "modifyThis");

            JSONArray occArr = new JSONArray();
            occArr.put(occ);

            JSONObject params = new JSONObject();
            params.put("token", mToken);
            params.put("occurrences", occArr);

            call("Occurrences.remove", params, false);
        } catch (JSONException e) {
            throw new IOException("deleteOccurrence: JSON Fehler: " + e.getMessage(), e);
        }
    }

    /**
     * Aktualisiert eine Occurrence (ein Vorkommen) auf dem Kerio-Server.
     *
     * Strategie:
     * 1) Occurrence per getById holen (damit wir ein vollständiges Objekt haben),
     * 2) relevante Felder überschreiben,
     * 3) per Occurrences.set zurückschreiben.
     */
    public void updateOccurrence(String occurrenceId, RemoteEvent updated) throws IOException {
        try {
            ensureLoggedIn();
        } catch (JSONException e) {
            throw new IOException("updateOccurrence: login/JSON Fehler: " + e.getMessage(), e);
        }

        if (occurrenceId == null || occurrenceId.trim().isEmpty()) {
            throw new IOException("updateOccurrence: occurrenceId fehlt");
        }
        if (updated == null) {
            throw new IOException("updateOccurrence: updated fehlt");
        }

        JSONObject existing = fetchOccurrenceById(occurrenceId);
        if (existing == null) {
            throw new IOException(
                    "updateOccurrence: Occurrence nicht gefunden (getById lieferte null): " + occurrenceId);
        }

        try {
            existing.put("summary", nullToEmpty(updated.summary));
            existing.put("location", nullToEmpty(updated.location));
            existing.put("description", nullToEmpty(updated.description));
            existing.put("isAllDay", updated.allDay);

            if (updated.dtStartUtcMillis > 0) {
                if (updated.allDay) {
                    // Kerio Date-only Start
                    existing.put("start", formatKerioDateOnlyFromUtcMillis(updated.dtStartUtcMillis));
                } else {
                    existing.put("start", formatKerioUtcDateTime(updated.dtStartUtcMillis));
                }
            }

            if (updated.dtEndUtcMillis > 0) {
                if (updated.allDay) {
                    // Android DTEND ist EXKLUSIV -> Kerio will INKLUSIV (bei Date-only)
                    existing.put("end", formatKerioInclusiveEndDateOnlyFromAndroidExclusiveEnd(
                            updated.dtEndUtcMillis,
                            updated.dtStartUtcMillis));
                } else {
                    existing.put("end", formatKerioUtcDateTime(updated.dtEndUtcMillis));
                }
            }

            existing.put("modification", "modifyThis");

            JSONArray occArr = new JSONArray();
            occArr.put(existing);

            JSONObject params = new JSONObject();
            params.put("token", mToken);
            params.put("occurrences", occArr);

            call("Occurrences.set", params, false);
        } catch (JSONException e) {
            throw new IOException("updateOccurrence: JSON Fehler: " + e.getMessage(), e);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * FIX: Lädt eine Occurrence als JSONObject über Occurrences.getById.
     *
     * Vorher war hier der zentrale Bug: du hast resp.optJSONArray("result")
     * genutzt,
     * aber Kerio liefert i.d.R. ein result-Objekt (JSONObject) zurück.
     */
    private JSONObject fetchOccurrenceById(String occurrenceId) throws IOException {
        try {
            JSONArray ids = new JSONArray();
            ids.put(occurrenceId);

            JSONObject params = new JSONObject();
            params.put("token", mToken);
            params.put("ids", ids);

            JSONObject resp = call("Occurrences.getById", params, true);

            // Kerio JSON-RPC: { "result": { "occurrences": [ ... ] } } (typisch)
            JSONObject resultObj = resp.optJSONObject("result");
            if (resultObj != null) {
                JSONArray occArr = resultObj.optJSONArray("occurrences");
                if (occArr == null)
                    occArr = resultObj.optJSONArray("list");
                if (occArr == null)
                    occArr = resultObj.optJSONArray("result");

                if (occArr != null && occArr.length() > 0) {
                    return occArr.getJSONObject(0);
                }
            }

            // Fallbacks (falls Server/Wrapper anders antwortet)
            JSONArray directArr = resp.optJSONArray("result");
            if (directArr != null && directArr.length() > 0) {
                return directArr.getJSONObject(0);
            }
            directArr = resp.optJSONArray("occurrences");
            if (directArr != null && directArr.length() > 0) {
                return directArr.getJSONObject(0);
            }
            directArr = resp.optJSONArray("list");
            if (directArr != null && directArr.length() > 0) {
                return directArr.getJSONObject(0);
            }

            return null;

        } catch (JSONException e) {
            throw new IOException("fetchOccurrenceById: JSON Fehler: " + e.getMessage(), e);
        }
    }

    /**
     * Helper: Für ein neu erstelltes Event (Events.create -> eventId) die
     * passende Occurrence-ID ermitteln.
     *
     * Idee:
     * - Occurrences.get im Zeitfenster um dtStart herum
     * - Filter: eventId == <eventId>
     *
     * @param calendarFolderId Kerio folderId (calendar.id)
     * @param eventId          Kerio eventId (von Events.create)
     * @param dtStartUtcMillis Startzeit (Millis)
     * @return occurrenceId oder null
     */
    public String resolveOccurrenceIdForEvent(String calendarFolderId, String eventId, long dtStartUtcMillis)
            throws IOException, JSONException {

        ensureLoggedIn();

        if (calendarFolderId == null || calendarFolderId.trim().isEmpty())
            return null;
        if (eventId == null || eventId.trim().isEmpty())
            return null;
        if (dtStartUtcMillis <= 0L)
            return null;

        // Zeitfenster: +/- 6 Stunden um DTSTART (robust gegen TZ/Server-Normalisierung)
        long startWindow = dtStartUtcMillis - (6L * 60L * 60L * 1000L);
        long endWindow = dtStartUtcMillis + (6L * 60L * 60L * 1000L);

        String windowStartStr = buildKerioUtcString(startWindow);
        String windowEndStr = buildKerioUtcString(endWindow + 1000L);

        JSONObject query = new JSONObject();

        JSONArray fields = new JSONArray();
        fields.put("id");
        fields.put("eventId");
        fields.put("start");
        fields.put("end");
        query.put("fields", fields);

        JSONArray conditions = new JSONArray();

        // eventId == <eventId>
        JSONObject condEventId = new JSONObject();
        condEventId.put("fieldName", "eventId");
        condEventId.put("comparator", "Equal");
        condEventId.put("value", eventId);
        conditions.put(condEventId);

        // start >= windowStart
        JSONObject condStartGe = new JSONObject();
        condStartGe.put("fieldName", "start");
        condStartGe.put("comparator", "GreaterEq");
        condStartGe.put("value", windowStartStr);
        conditions.put(condStartGe);

        // end < windowEnd (Kerio-Constraint)
        JSONObject condEndLt = new JSONObject();
        condEndLt.put("fieldName", "end");
        condEndLt.put("comparator", "LessThan");
        condEndLt.put("value", windowEndStr);
        conditions.put(condEndLt);

        query.put("conditions", conditions);
        query.put("combining", "And");
        query.put("start", 0);
        query.put("limit", 50);

        JSONObject params = new JSONObject();
        params.put("token", mToken);
        params.put("folderIds", new JSONArray().put(calendarFolderId));
        params.put("query", query);

        JSONObject resp = call("Occurrences.get", params, true);

        JSONObject resultObj = resp.optJSONObject("result");
        if (resultObj == null)
            return null;

        JSONArray list = resultObj.optJSONArray("list");
        if (list == null)
            list = resultObj.optJSONArray("occurrences");
        if (list == null)
            return null;

        // Best match: occurrence mit start am nächsten an dtStartUtcMillis
        String bestId = null;
        long bestDelta = Long.MAX_VALUE;

        for (int i = 0; i < list.length(); i++) {
            JSONObject occ = list.optJSONObject(i);
            if (occ == null)
                continue;

            String occId = occ.optString("id", null);
            String occEventId = occ.optString("eventId", null);
            String startStr = occ.optString("start", null);

            if (occId == null || occId.isEmpty())
                continue;
            if (!eventId.equals(occEventId))
                continue;

            long startMillis = 0L;
            if (startStr != null && !startStr.isEmpty()) {
                startMillis = parseKerioUtcDateTimeString(startStr);
            }
            if (startMillis <= 0L)
                continue;

            long delta = Math.abs(startMillis - dtStartUtcMillis);
            if (delta < bestDelta) {
                bestDelta = delta;
                bestId = occId;
            }
        }

        return bestId;
    }

    // ---------------------------------------------------------------------
    // SSL Helper Hook (für KerioSslHelper)
    // ---------------------------------------------------------------------

    public static SSLSocketFactory buildCustomCaSocketFactory(InputStream caInputStream)
            throws Exception {
        java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
        java.security.cert.Certificate ca = cf.generateCertificate(caInputStream);

        java.security.KeyStore ks = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setCertificateEntry("ca", ca);

        javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory
                .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);

        javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), new java.security.SecureRandom());
        return ctx.getSocketFactory();
    }

    // ------------------------------------------------------------------------
    // JSON-RPC Call
    // ------------------------------------------------------------------------
    public synchronized JSONObject call(String method,
            JSONObject params,
            boolean expectResult) throws IOException, JSONException {

        if (params == null) {
            params = new JSONObject();
        }

        long id = mRequestId.getAndIncrement();

        JSONObject request = new JSONObject();
        request.put("jsonrpc", JSON_RPC_VERSION);
        request.put("id", id);
        request.put("method", method);
        request.put("params", params);

        String requestBody = request.toString();

        HttpURLConnection conn = null;
        String respBody;

        try {
            URL url = new URL(mApiUrl);
            conn = (HttpURLConnection) url.openConnection();

            if (conn instanceof HttpsURLConnection) {
                HttpsURLConnection https = (HttpsURLConnection) conn;

                if (mTrustAllCerts) {
                    Log.w(TAG, "Verwende UNSICHEREN trust-all SSL-Context – nur für Tests geeignet!");
                    SSLSocketFactory unsafeFactory = getUnsafeSslSocketFactory();
                    HostnameVerifier unsafeVerifier = getUnsafeHostnameVerifier();
                    https.setSSLSocketFactory(unsafeFactory);
                    https.setHostnameVerifier(unsafeVerifier);
                } else if (mCustomSslSocketFactory != null) {
                    Log.d(TAG, "Verwende Custom-SSLSocketFactory für HTTPS-Verbindung.");
                    https.setSSLSocketFactory(mCustomSslSocketFactory);
                }
            }

            conn.setRequestMethod("POST");
            conn.setDoInput(true);
            conn.setDoOutput(true);
            conn.setUseCaches(false);

            conn.setRequestProperty("Accept", "application/json-rpc");
            conn.setRequestProperty("Content-Type", "application/json-rpc; charset=UTF-8");

            if (mToken != null) {
                conn.setRequestProperty("X-Token", mToken);
            }

            String cookieHeader = buildCookieHeader();
            if (!cookieHeader.isEmpty()) {
                conn.setRequestProperty("Cookie", cookieHeader);
            }

            Log.d(TAG, "Sende JSON-RPC-Request: method=" + method + ", id=" + id);

            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8));
            writer.write(requestBody);
            writer.flush();
            writer.close();

            int status = conn.getResponseCode();
            Log.d(TAG, "HTTP-Status: " + status + " für Methode " + method);

            InputStream is = (status >= 200 && status < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            respBody = readStreamToString(is);
            if (respBody == null) {
                respBody = "";
            }

            updateCookiesFromConnection(conn);

            if (status < 200 || status >= 300) {
                Log.e(TAG, "HTTP-Fehler beim JSON-RPC-Call: status=" + status +
                        ", body=" + respBody);
                throw new IOException("HTTP-Fehler " + status + " bei JSON-RPC-Call " + method);
            }

            JSONObject respJson = new JSONObject(respBody);

            if (respJson.has("error")) {
                JSONObject error = respJson.getJSONObject("error");
                int code = error.optInt("code", 0);
                String message = error.optString("message", "Unbekannter Fehler");
                Log.e(TAG, "Kerio JSON-RPC-Error: code=" + code + ", message=" + message);
                throw new IOException("Kerio JSON-RPC Error " + code + ": " + message);
            }

            if (expectResult && !respJson.has("result")) {
                Log.w(TAG, "JSON-RPC-Antwort ohne result-Feld bei Methode " + method);
            }

            return respJson;

        } catch (IOException e) {
            Log.e(TAG, "IOException beim JSON-RPC-Call " + method + ": " + e.getMessage(), e);
            throw e;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String readStreamToString(InputStream is) throws IOException {
        if (is == null) {
            return null;
        }
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int len;
        while ((len = reader.read(buf)) != -1) {
            sb.append(buf, 0, len);
        }
        reader.close();
        return sb.toString();
    }

    private void updateCookiesFromConnection(HttpURLConnection conn) {
        Map<String, List<String>> headerFields = conn.getHeaderFields();
        if (headerFields == null) {
            return;
        }

        List<String> setCookies = headerFields.get("Set-Cookie");
        if (setCookies == null) {
            return;
        }

        for (String header : setCookies) {
            if (header == null || header.isEmpty()) {
                continue;
            }

            String[] parts = header.split(";", 2);
            if (parts.length == 0) {
                continue;
            }

            String[] nv = parts[0].split("=", 2);
            if (nv.length != 2) {
                continue;
            }

            String name = nv[0].trim();
            String value = nv[1].trim();

            if (!name.isEmpty()) {
                mCookies.put(name, value);
            }
        }
    }

    private String buildCookieHeader() {
        if (mCookies.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : mCookies.entrySet()) {
            if (!first) {
                sb.append("; ");
            }
            first = false;
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        return sb.toString();
    }

    private static SSLSocketFactory getUnsafeSslSocketFactory() throws IOException {
        if (sUnsafeSslSocketFactory != null) {
            return sUnsafeSslSocketFactory;
        }

        try {
            TrustManager[] trustAllCerts = new TrustManager[] {
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[0];
                        }
                    }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            sUnsafeSslSocketFactory = sslContext.getSocketFactory();
            return sUnsafeSslSocketFactory;
        } catch (Exception e) {
            throw new IOException("Konnte unsafe SSLContext nicht initialisieren: " + e.getMessage(), e);
        }
    }

    private static HostnameVerifier getUnsafeHostnameVerifier() {
        if (sUnsafeHostnameVerifier != null) {
            return sUnsafeHostnameVerifier;
        }
        sUnsafeHostnameVerifier = (hostname, session) -> true;
        return sUnsafeHostnameVerifier;
    }

    private long parseKerioUtcDateTimeString(String kerioDateTime) {
        if (kerioDateTime == null || kerioDateTime.isEmpty()) {
            return 0L;
        }

        final String v = kerioDateTime.trim();

        // Date-only fallback: als Start-of-day UTC interpretieren
        if (isKerioDateOnly(v)) {
            try {
                return parseKerioDateOnlyStartUtcMillis(v);
            } catch (Exception e) {
                Log.w(TAG, "Konnte Kerio Date-only nicht parsen: '" + v + "'", e);
                return 0L;
            }
        }

        // 1) Kerio liefert bei Ganztags-Terminen teils nur ein Datum: yyyyMMdd (z. B.
        // 20251217)
        // Wir interpretieren dies als 00:00:00 UTC.
        if (v.matches("^\\d{8}$")) {
            try {
                LocalDate d = LocalDate.parse(v, KERIO_DATE_ONLY_FORMAT);
                return d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            } catch (Exception e) {
                Log.w(TAG, "Konnte Kerio Date-only nicht parsen: '" + v + "'", e);
                return 0L;
            }
        }

        // 2) UTC mit 'Z': 20251210T110355Z
        try {
            LocalDateTime ldt = LocalDateTime.parse(v, KERIO_UTC_Z_FORMAT);
            return ldt.toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException ignored) {
        } catch (Exception e) {
            Log.w(TAG, "Konnte Kerio UTC(Z) nicht parsen: '" + v + "'", e);
        }

        // 3) Offset ohne Doppelpunkt: 20251210T120000+0100
        try {
            OffsetDateTime odt = OffsetDateTime.parse(v, KERIO_OFFSET_NO_COLON_FORMAT);
            return odt.toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
        } catch (Exception e) {
            Log.w(TAG, "Konnte Kerio Offset(+HHmm) nicht parsen: '" + v + "'", e);
        }

        // 4) Offset mit Doppelpunkt: 20251210T120000+01:00
        try {
            OffsetDateTime odt = OffsetDateTime.parse(v, KERIO_OFFSET_COLON_FORMAT);
            return odt.toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
        } catch (Exception e) {
            Log.w(TAG, "Konnte Kerio Offset(+HH:mm) nicht parsen: '" + v + "'", e);
        }

        // 5) ISO-8601 fallback
        try {
            return Instant.parse(v).toEpochMilli();
        } catch (Exception e) {
            Log.w(TAG, "Konnte Kerio DateTime nicht parsen: '" + v + "'", e);
            return 0L;
        }
    }

    private String buildKerioUtcString(long utcMillis) {
        Instant instant = Instant.ofEpochMilli(utcMillis);
        return KERIO_UTC_Z_FORMAT.withZone(ZoneOffset.UTC).format(instant);
    }

    private static boolean isKerioDateOnly(String v) {
        return v != null && v.matches("^\\d{8}$");
    }

    /**
     * Kerio Date-only Start: yyyyMMdd -> 00:00 UTC (Millis)
     */
    private static long parseKerioDateOnlyStartUtcMillis(String yyyymmdd) {
        LocalDate d = LocalDate.parse(yyyymmdd, KERIO_DATE_ONLY_FORMAT);
        return d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    /**
     * Kerio Date-only End ist bei deinem Server INKLUSIV.
     * Android erwartet DTEND bei All-Day EXKLUSIV (00:00 des Folgetags).
     *
     * yyyyMMdd (inkl. Endtag) -> (Endtag + 1) 00:00 UTC (Millis)
     */
    private static long parseKerioDateOnlyEndExclusiveUtcMillis(String yyyymmddInclusiveEnd) {
        LocalDate d = LocalDate.parse(yyyymmddInclusiveEnd, KERIO_DATE_ONLY_FORMAT);
        return d.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    /**
     * Format yyyyMMdd aus UTC-Millis (nimmt das UTC-Datum).
     */
    private static String formatKerioDateOnlyFromUtcMillis(long utcMillis) {
        LocalDate d = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate();
        return d.format(KERIO_DATE_ONLY_FORMAT);
    }

    /**
     * Android liefert bei All-Day DTEND EXKLUSIV.
     * Kerio erwartet bei Date-only hier INKLUSIV -> also ein Tag zurück.
     */
    private static String formatKerioInclusiveEndDateOnlyFromAndroidExclusiveEnd(long dtEndExclusiveUtcMillis,
            long dtStartUtcMillis) {
        if (dtEndExclusiveUtcMillis <= 0) {
            // Fallback: wenn kein Ende da ist, nimm Start als 1-Tages Event
            return formatKerioDateOnlyFromUtcMillis(dtStartUtcMillis);
        }

        LocalDate endExclusiveDate = Instant.ofEpochMilli(dtEndExclusiveUtcMillis).atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate inclusiveEndDate = endExclusiveDate.minusDays(1);

        // Safety: falls End <= Start, auf Start clampen
        if (dtStartUtcMillis > 0) {
            LocalDate startDate = Instant.ofEpochMilli(dtStartUtcMillis).atZone(ZoneOffset.UTC).toLocalDate();
            if (inclusiveEndDate.isBefore(startDate)) {
                inclusiveEndDate = startDate;
            }
        }

        return inclusiveEndDate.format(KERIO_DATE_ONLY_FORMAT);
    }

    // ---------------------------------------------------------------------
    // Kontakte: Server -> Client (Personal + Shared)
    // ---------------------------------------------------------------------

    /**
     * Einfaches Kontaktmodell für die Kontakt-Synchronisation (Server -> Client).
     *
     * Wichtig:
     * - id kann in unterschiedlichen Adressbüchern (folderId) vorkommen, daher ist
     * die Kombination folderId:id als eindeutiger Schlüssel zu betrachten.
     */
    public static class RemoteContact {
        /** Kontakt-ID */
        public String id;

        /** Adressbuch/Folder-ID */
        public String folderId;

        /** Anzeigename des Adressbuchs (sofern verfügbar) */
        public String folderName;

        /** optional: Watermark/ETag (wenn Kerio liefert) */
        public String watermark;

        public String commonName;
        public String firstName;
        public String middleName;
        public String surName;
        public String nickName;

        public final java.util.List<String> emails = new java.util.ArrayList<>();
        public final java.util.List<String> phones = new java.util.ArrayList<>();
    }

    /**
     * Repräsentiert ein Kerio-Kontakt-Adressbuch (Folder).
     *
     * Wird für Android-Gruppen (ContactsContract.Groups) genutzt, damit
     * die Kontakt-App den Kontotyp + "Gruppen/Ordner" korrekt anbieten kann.
     */
    public static class RemoteContactFolder {
        /** Folder-ID des Adressbuchs */
        public String id;

        /** Ordnername laut Kerio */
        public String name;

        /** Optionaler DisplayName, den wir für Android "Gruppen" verwenden */
        public String displayName;

        /** True, wenn Folder aus Public stammt */
        public boolean isPublic;

        /** True, wenn Folder aus Shared/Delegated stammt */
        public boolean isShared;

        /** Owner/Benutzername (bei Shared/Delegated) */
        public String owner;

        /** Owner-E-Mail (wenn geliefert) */
        public String ownerEmail;

        @Override
        public String toString() {
            return "RemoteContactFolder{" +
                    "id='" + id + '\'' +
                    ", name='" + name + '\'' +
                    ", displayName='" + displayName + '\'' +
                    ", isPublic=" + isPublic +
                    ", isShared=" + isShared +
                    ", owner='" + owner + '\'' +
                    ", ownerEmail='" + ownerEmail + '\'' +
                    '}';
        }
    }

    /**
     * Liefert alle Kontakt-Ordner (Adressbücher) inkl. Public + Shared (wenn vom
     * Server unterstützt).
     *
     * Wichtig:
     * - Kerio-Versionen variieren stark: manche liefern Kontakt-Ordner in
     * Folders.get,
     * andere nur über SharedMailboxList/Public.
     * - Wir versuchen daher "best effort": Fehler einzelner Calls sind nicht fatal.
     */
    public java.util.List<RemoteContactFolder> fetchContactFolders()
            throws java.io.IOException, org.json.JSONException {

        ensureLoggedIn();

        java.util.Map<String, RemoteContactFolder> byId = new java.util.HashMap<>();

        // (1) Eigene Folder
        try {
            org.json.JSONObject params = new org.json.JSONObject();
            params.put("token", mToken);
            org.json.JSONObject resp = call("Folders.get", params, true);
            addContactFoldersFromFolderResult(resp, byId, false, false, null, null);
        } catch (Exception e) {
            android.util.Log.i(TAG, "fetchContactFolders(): Folders.get fehlgeschlagen: " + e.getMessage());
        }

        // (2) Public Folder (optional)
        try {
            org.json.JSONObject params = new org.json.JSONObject();
            params.put("token", mToken);
            org.json.JSONObject resp = call("Folders.getPublic", params, true);
            addContactFoldersFromFolderResult(resp, byId, true, false, null, null);
        } catch (Exception e) {
            android.util.Log.i(TAG,
                    "fetchContactFolders(): Folders.getPublic nicht verfügbar/fehlgeschlagen: " + e.getMessage());
        }

        // (3) Shared Mailboxes (optional)
        try {
            org.json.JSONObject params = new org.json.JSONObject();
            params.put("token", mToken);
            org.json.JSONObject resp = call("Folders.getSharedMailboxList", params, true);
            addContactFoldersFromSharedMailboxListResult(resp, byId);
        } catch (Exception e) {
            android.util.Log.i(TAG,
                    "fetchContactFolders(): Folders.getSharedMailboxList nicht verfügbar/fehlgeschlagen: "
                            + e.getMessage());
        }

        return new java.util.ArrayList<>(byId.values());
    }

    private void addContactFoldersFromFolderResult(org.json.JSONObject resp,
            java.util.Map<String, RemoteContactFolder> byId,
            boolean isPublic,
            boolean isShared,
            String forcedOwnerName,
            String forcedOwnerEmail) throws org.json.JSONException {

        if (resp == null || !resp.has("result")) {
            return;
        }

        org.json.JSONObject result = resp.getJSONObject("result");

        // Kerio-Versionen variieren: result.list[] oder result.mailboxes[]
        org.json.JSONArray list = result.optJSONArray("list");
        if (list == null) {
            list = result.optJSONArray("mailboxes");
        }
        if (list == null) {
            return;
        }

        for (int i = 0; i < list.length(); i++) {
            org.json.JSONObject mb = list.optJSONObject(i);
            if (mb == null) {
                continue;
            }

            // Manche Kerio-Versionen liefern "folders" in mailbox-Objekten, manche direkt
            // list[] = folders
            org.json.JSONArray folders = mb.optJSONArray("folders");
            if (folders == null) {
                folders = mb.optJSONArray("list");
            }
            if (folders == null) {
                // Falls list[] bereits Folder ist
                if (mb.has("type") && mb.has("id")) {
                    RemoteContactFolder f = folderToRemoteContactFolder(mb, isPublic, isShared, forcedOwnerName,
                            forcedOwnerEmail);
                    if (f != null && f.id != null && !f.id.isEmpty()) {
                        RemoteContactFolder existing = byId.get(f.id);
                        if (existing == null)
                            byId.put(f.id, f);
                    }
                }
                continue;
            }

            for (int f = 0; f < folders.length(); f++) {
                org.json.JSONObject folder = folders.optJSONObject(f);
                if (folder == null) {
                    continue;
                }

                RemoteContactFolder cf = folderToRemoteContactFolder(folder, isPublic, isShared, forcedOwnerName,
                        forcedOwnerEmail);
                if (cf == null || cf.id == null || cf.id.isEmpty()) {
                    continue;
                }

                RemoteContactFolder existing = byId.get(cf.id);
                if (existing == null) {
                    byId.put(cf.id, cf);
                } else {
                    // Merge: prefer gefüllte Felder
                    if ((existing.name == null || existing.name.isEmpty()) && cf.name != null)
                        existing.name = cf.name;
                    if ((existing.displayName == null || existing.displayName.isEmpty()) && cf.displayName != null)
                        existing.displayName = cf.displayName;
                    existing.isPublic = existing.isPublic || cf.isPublic;
                    existing.isShared = existing.isShared || cf.isShared;
                    if ((existing.owner == null || existing.owner.isEmpty()) && cf.owner != null)
                        existing.owner = cf.owner;
                    if ((existing.ownerEmail == null || existing.ownerEmail.isEmpty()) && cf.ownerEmail != null)
                        existing.ownerEmail = cf.ownerEmail;
                }
            }
        }
    }

    private void addContactFoldersFromSharedMailboxListResult(org.json.JSONObject resp,
            java.util.Map<String, RemoteContactFolder> byId) throws org.json.JSONException {

        if (resp == null || !resp.has("result")) {
            return;
        }

        org.json.JSONObject result = resp.getJSONObject("result");

        // Kerio-Versionen variieren: "mailboxes" oder "list"
        org.json.JSONArray mailboxes = result.optJSONArray("mailboxes");
        if (mailboxes == null) {
            mailboxes = result.optJSONArray("list");
        }
        if (mailboxes == null) {
            return;
        }

        for (int i = 0; i < mailboxes.length(); i++) {
            org.json.JSONObject mb = mailboxes.optJSONObject(i);
            if (mb == null) {
                continue;
            }

            String mailboxOwnerName = null;
            String mailboxOwnerEmail = null;

            org.json.JSONObject principal = mb.optJSONObject("principal");
            if (principal != null) {
                mailboxOwnerName = principal.optString("name", null);
                mailboxOwnerEmail = principal.optString("emailAddress", null);
                if (mailboxOwnerEmail == null || mailboxOwnerEmail.isEmpty()) {
                    mailboxOwnerEmail = principal.optString("email", null);
                }
            }

            org.json.JSONArray folders = mb.optJSONArray("folders");
            if (folders == null) {
                continue;
            }

            for (int f = 0; f < folders.length(); f++) {
                org.json.JSONObject folder = folders.optJSONObject(f);
                if (folder == null) {
                    continue;
                }

                RemoteContactFolder cf = folderToRemoteContactFolder(folder, false, true, mailboxOwnerName,
                        mailboxOwnerEmail);
                if (cf == null || cf.id == null || cf.id.isEmpty()) {
                    continue;
                }

                RemoteContactFolder existing = byId.get(cf.id);
                if (existing == null) {
                    byId.put(cf.id, cf);
                } else {
                    if ((existing.name == null || existing.name.isEmpty()) && cf.name != null)
                        existing.name = cf.name;
                    if ((existing.displayName == null || existing.displayName.isEmpty()) && cf.displayName != null)
                        existing.displayName = cf.displayName;
                    existing.isPublic = existing.isPublic || cf.isPublic;
                    existing.isShared = existing.isShared || cf.isShared;
                    if ((existing.owner == null || existing.owner.isEmpty()) && cf.owner != null)
                        existing.owner = cf.owner;
                    if ((existing.ownerEmail == null || existing.ownerEmail.isEmpty()) && cf.ownerEmail != null)
                        existing.ownerEmail = cf.ownerEmail;
                }
            }
        }
    }

    private RemoteContactFolder folderToRemoteContactFolder(org.json.JSONObject folder,
            boolean isPublic,
            boolean isShared,
            String forcedOwnerName,
            String forcedOwnerEmail) {

        if (folder == null) {
            return null;
        }

        String type = folder.optString("type", "");

        // Kerio-Versionen variieren:
        // - teils "contact", "contacts", "addressbook"
        // - teils "FContact" / "FContacts" / "FAddressBook"
        // - teils "FContactFolder"
        boolean isContactFolder = "contact".equalsIgnoreCase(type)
                || "contacts".equalsIgnoreCase(type)
                || "addressbook".equalsIgnoreCase(type)
                || "fcontact".equalsIgnoreCase(type)
                || "fcontacts".equalsIgnoreCase(type)
                || "faddressbook".equalsIgnoreCase(type)
                || "fcontactfolder".equalsIgnoreCase(type)
                || type.toLowerCase(java.util.Locale.ROOT).contains("contact");

        if (!isContactFolder) {
            return null;
        }

        RemoteContactFolder cf = new RemoteContactFolder();

        cf.id = folder.optString("id", null);
        if (cf.id == null || cf.id.isEmpty()) {
            return null;
        }

        cf.name = folder.optString("name", cf.id);
        cf.isPublic = isPublic;
        cf.isShared = isShared;

        String ownerFromFolder = folder.optString("ownerName",
                folder.optString("owner", ""));
        if (ownerFromFolder == null || ownerFromFolder.isEmpty()) {
            ownerFromFolder = forcedOwnerName;
        }
        if (ownerFromFolder == null || ownerFromFolder.isEmpty()) {
            ownerFromFolder = mUsername;
        }
        cf.owner = ownerFromFolder;

        String ownerEmail = folder.optString("emailAddress", null);
        if (ownerEmail == null || ownerEmail.isEmpty()) {
            ownerEmail = forcedOwnerEmail;
        }
        cf.ownerEmail = ownerEmail;

        // DisplayName: Shared/Public in Android besser erkennbar machen
        if (cf.isPublic) {
            cf.displayName = "Public: " + cf.name;
        } else if (cf.isShared) {
            cf.displayName = cf.owner + ": " + cf.name;
        } else {
            cf.displayName = cf.name;
        }

        return cf;
    }

    /**
     * Lädt Kontakte aus Kerio.
     *
     * @param folderIds Optional. Wenn null/leer, werden Kontakt-Ordner via
     *                  Folders.get/Public/SharedMailboxList automatisch ermittelt.
     */
    public java.util.List<RemoteContact> fetchContacts(java.util.List<String> folderIds)
            throws java.io.IOException, org.json.JSONException {

        ensureLoggedIn();

        java.util.List<String> contactFolderIds = new java.util.ArrayList<>();
        java.util.Map<String, String> folderNameById = new java.util.HashMap<>();

        if (folderIds != null && !folderIds.isEmpty()) {
            contactFolderIds.addAll(folderIds);
        } else {
            // Kontakt-Folder inkl. Shared/Public best-effort holen
            java.util.List<RemoteContactFolder> folders = fetchContactFolders();
            for (RemoteContactFolder f : folders) {
                if (f == null || f.id == null || f.id.isEmpty())
                    continue;
                contactFolderIds.add(f.id);
                // für Kontakte nutzen wir den DisplayName, damit Kontakte-App "Ordner"
                // erkennbar darstellt
                folderNameById.put(f.id, (f.displayName != null) ? f.displayName : f.name);
            }
        }

        java.util.List<RemoteContact> out = new java.util.ArrayList<>();
        if (contactFolderIds.isEmpty()) {
            android.util.Log.w(TAG, "fetchContacts(): Keine Kontakt-Ordner gefunden.");
            return out;
        }

        final int LIMIT = 200;
        int start = 0;
        long total = Long.MAX_VALUE;

        while (start < total) {
            org.json.JSONObject query = new org.json.JSONObject();
            query.put("start", start);
            query.put("limit", LIMIT);

            org.json.JSONArray fields = new org.json.JSONArray();
            fields.put("id");
            fields.put("folderId");
            fields.put("watermark");
            fields.put("commonName");
            fields.put("firstName");
            fields.put("middleName");
            fields.put("surName");
            fields.put("nickName");
            fields.put("emailAddresses");
            fields.put("phoneNumbers");
            query.put("fields", fields);

            org.json.JSONObject params = new org.json.JSONObject();
            params.put("token", mToken);
            params.put("folderIds", new org.json.JSONArray(contactFolderIds));
            params.put("query", query);

            org.json.JSONObject response = call("Contacts.get", params, true);
            org.json.JSONObject result = (response != null) ? response.optJSONObject("result") : null;
            if (result == null) {
                android.util.Log.w(TAG, "fetchContacts(): result == null");
                break;
            }

            total = result.optLong("totalItems", 0);
            org.json.JSONArray list = result.optJSONArray("list");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    org.json.JSONObject c = list.optJSONObject(i);
                    if (c == null)
                        continue;

                    RemoteContact rc = new RemoteContact();
                    rc.id = c.optString("id", null);
                    rc.folderId = c.optString("folderId", null);
                    rc.folderName = (rc.folderId != null) ? folderNameById.get(rc.folderId) : null;
                    rc.watermark = c.optString("watermark", null);
                    rc.commonName = c.optString("commonName", null);
                    rc.firstName = c.optString("firstName", null);
                    rc.middleName = c.optString("middleName", null);
                    rc.surName = c.optString("surName", null);
                    rc.nickName = c.optString("nickName", null);

                    org.json.JSONArray emails = c.optJSONArray("emailAddresses");
                    if (emails != null) {
                        for (int e = 0; e < emails.length(); e++) {
                            Object v = emails.opt(e);
                            if (v instanceof org.json.JSONObject) {
                                org.json.JSONObject o = (org.json.JSONObject) v;
                                String email = o.optString("email", null);
                                if (email == null || email.isEmpty())
                                    email = o.optString("address", null);
                                if (email != null && !email.isEmpty())
                                    rc.emails.add(email);
                            } else if (v instanceof String) {
                                String email = (String) v;
                                if (!email.isEmpty())
                                    rc.emails.add(email);
                            }
                        }
                    }

                    org.json.JSONArray phones = c.optJSONArray("phoneNumbers");
                    if (phones != null) {
                        for (int p = 0; p < phones.length(); p++) {
                            Object v = phones.opt(p);
                            if (v instanceof org.json.JSONObject) {
                                org.json.JSONObject o = (org.json.JSONObject) v;
                                String number = o.optString("number", null);
                                if (number == null || number.isEmpty())
                                    number = o.optString("phone", null);
                                if (number != null && !number.isEmpty())
                                    rc.phones.add(number);
                            } else if (v instanceof String) {
                                String number = (String) v;
                                if (!number.isEmpty())
                                    rc.phones.add(number);
                            }
                        }
                    }

                    out.add(rc);
                }
            }

            start += LIMIT;
        }

        android.util.Log.i(TAG, "fetchContacts(): fetched=" + out.size() + " folders=" + contactFolderIds.size());
        return out;
    }

}
