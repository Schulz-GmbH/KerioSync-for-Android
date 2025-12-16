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
    private static final long DEFAULT_PAST_WINDOW_MS = 180L * 24L * 60L * 60L * 1000L;   // 180 Tage zurück
    private static final long DEFAULT_FUTURE_WINDOW_MS = 365L * 24L * 60L * 60L * 1000L; // 365 Tage vor

    /**
     * Kerio DateTime Strings (RFC2445-Style):
     * - UTC:    20251210T110355Z
     * - Offset: 20251210T120000+0100
     * - ggf.:   20251210T120000+01:00
     *
     * Kerio-Doku nennt UtcDateTime als string, in der Praxis kommt häufig Offset.
     */
    private static final DateTimeFormatter KERIO_UTC_Z_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US);

    private static final DateTimeFormatter KERIO_OFFSET_NO_COLON_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssZ", Locale.US); // +0100

    private static final DateTimeFormatter KERIO_OFFSET_COLON_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssXXX", Locale.US); // +01:00

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
        public String owner;
        public boolean readOnly;
        public String color;

        @Override
        public String toString() {
            return "RemoteCalendar{" +
                    "id='" + id + '\'' +
                    ", name='" + name + '\'' +
                    ", owner='" + owner + '\'' +
                    ", readOnly=" + readOnly +
                    ", color='" + color + '\'' +
                    '}';
        }
    }

    public static class RemoteEvent {
        public String uid;
        public String eventId;
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
        public String id;

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

    List<RemoteCalendar> calendars = new ArrayList<>();

    JSONObject params = new JSONObject();
    JSONObject resp = call("Folders.get", params, true);

    if (!resp.has("result")) {
        return calendars;
    }

    JSONObject result = resp.getJSONObject("result");
    JSONArray folderList = result.optJSONArray("list");
    if (folderList == null) {
        return calendars;
    }

    for (int i = 0; i < folderList.length(); i++) {
        JSONObject folder = folderList.optJSONObject(i);
        if (folder == null) {
            continue;
        }

        String type = folder.optString("type", "");
        if (!"FCalendar".equals(type)) {
            continue;
        }

        RemoteCalendar rc = new RemoteCalendar();

        rc.id = folder.optString("id", null);
        if (rc.id == null || rc.id.isEmpty()) {
            continue;
        }

        rc.name = folder.optString("name", rc.id);

        String ownerFromFolder = folder.optString("ownerName",
                folder.optString("owner", ""));
        if (ownerFromFolder == null || ownerFromFolder.isEmpty()) {
            ownerFromFolder = mUsername;
        }
        rc.owner = ownerFromFolder;

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

        calendars.add(rc);
    }

    return calendars;
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
            if (occ == null) {
                continue;
            }

            RemoteEvent evt = new RemoteEvent();

            evt.uid = occ.optString("id", null);
            evt.eventId = occ.optString("eventId", null);
            evt.calendarId = calendar.id;

            evt.summary = occ.optString("summary", "");
            evt.description = occ.optString("description", "");
            evt.location = occ.optString("location", "");

            String startStr = occ.optString("start", null);
            if (startStr != null && !startStr.isEmpty()) {
                evt.dtStartUtcMillis = parseKerioUtcDateTimeString(startStr);
            }

            String endStr = occ.optString("end", null);
            if (endStr != null && !endStr.isEmpty()) {
                evt.dtEndUtcMillis = parseKerioUtcDateTimeString(endStr);
            }

            String lmStr = occ.optString("lastModificationTime", null);
            if (lmStr != null && !lmStr.isEmpty()) {
                evt.lastModifiedUtcMillis = parseKerioUtcDateTimeString(lmStr);
                evt.lastModifiedUtc = evt.lastModifiedUtcMillis;
            } else {
                evt.lastModifiedUtcMillis = 0L;
                evt.lastModifiedUtc = 0L;
            }

            evt.allDay = occ.optBoolean("isAllDay", false);

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
        params.put("token", token);
        params.put("folderIds", new JSONArray().put(calendar.id));
        params.put("query", query);

        JSONObject response = call("Occurrences.get", params, true);

        parseEventsResponse(response, calendar, events);

        return events;
    }

    /**
     * ✅ NEU: Event auf dem Server anlegen (Events.create)
     *
     * Wird vom SyncAdapter genutzt, um lokale neu erstellte Termine zum Server zu pushen.
     *
     * Request:
     * params: { token, events: [ { folderId, summary, location, description, isAllDay, start, end } ] }
     *
     * Response (typisch):
     * result: { errors: [...], result: [ { inputIndex, id, ... } ] }
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

        ev.put("start", buildKerioUtcString(event.dtStartUtcMillis));
        ev.put("end", buildKerioUtcString(event.dtEndUtcMillis));

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
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) { }

                        @Override
                        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) { }

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

        try {
            LocalDateTime ldt = LocalDateTime.parse(kerioDateTime, KERIO_UTC_Z_FORMAT);
            return ldt.toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException ignored) {
        } catch (Exception e) {
            Log.w(TAG, "Konnte Kerio UTC(Z) nicht parsen: '" + kerioDateTime + "'", e);
        }

        try {
            OffsetDateTime odt = OffsetDateTime.parse(kerioDateTime, KERIO_OFFSET_NO_COLON_FORMAT);
            return odt.toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
        } catch (Exception e) {
            Log.w(TAG, "Konnte Kerio Offset(+HHmm) nicht parsen: '" + kerioDateTime + "'", e);
        }

        try {
            OffsetDateTime odt = OffsetDateTime.parse(kerioDateTime, KERIO_OFFSET_COLON_FORMAT);
            return odt.toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
        } catch (Exception e) {
            Log.w(TAG, "Konnte Kerio Offset(+HH:mm) nicht parsen: '" + kerioDateTime + "'", e);
        }

        try {
            return Instant.parse(kerioDateTime).toEpochMilli();
        } catch (Exception e) {
            Log.w(TAG, "Konnte Kerio DateTime nicht parsen: '" + kerioDateTime + "'", e);
            return 0L;
        }
    }

    private String buildKerioUtcString(long utcMillis) {
        Instant instant = Instant.ofEpochMilli(utcMillis);
        return KERIO_UTC_Z_FORMAT.withZone(ZoneOffset.UTC).format(instant);
    }
}
