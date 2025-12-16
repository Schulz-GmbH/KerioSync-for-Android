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
import java.nio.charset.StandardCharsets;
import java.net.HttpURLConnection;
import java.net.URL;
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
 * Erwartet eine JSON-RPC-API-URL, z. B.:
 * https://host/webmail/api/jsonrpc/
 *
 * Der Konstruktor normalisiert die URL, falls nur der Host angegeben wird:
 * "https://host" --> "https://host/webmail/api/jsonrpc/"
 * "host" --> "https://host/webmail/api/jsonrpc/"
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
     *
     * Beispiele:
     * "sh-dc1.schulz-hygiene.de"
     * "https://sh-dc1.schulz-hygiene.de"
     *
     * werden zu:
     * "https://sh-dc1.schulz-hygiene.de/webmail/api/jsonrpc/"
     *
     * Wenn bereits "jsonrpc" in der URL vorkommt, wird nichts angehängt.
     */
    private static String normalizeKerioApiUrl(String apiUrl) {
        if (apiUrl == null) {
            return null;
        }

        apiUrl = apiUrl.trim();

        if (!apiUrl.startsWith("http://") && !apiUrl.startsWith("https://")) {
            apiUrl = "https://" + apiUrl;
        }

        // Eventuelle nachträgliche Slashes am Ende entfernen
        while (apiUrl.endsWith("/")) {
            apiUrl = apiUrl.substring(0, apiUrl.length() - 1);
        }

        String lower = apiUrl.toLowerCase(Locale.ROOT);
        if (!lower.contains("jsonrpc")) {
            // Standard-Kerio-Endpoint anhängen
            apiUrl = apiUrl + "/webmail/api/jsonrpc/";
        } else {
            // Wenn Benutzer bereits komplette URL inkl. jsonrpc angegeben hat, nichts verändern
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

        JSONArray occurrences = result.optJSONArray("occurrences");
        if (occurrences == null) {
            Log.i(TAG, "parseEventsResponse(): keine occurrences im Ergebnis");
            return;
        }

        for (int i = 0; i < occurrences.length(); i++) {
            JSONObject occ = occurrences.optJSONObject(i);
            if (occ == null) {
                continue;
            }

            JSONObject eventJson = occ.optJSONObject("event");
            if (eventJson == null) {
                continue;
            }

            RemoteEvent evt = new RemoteEvent();

            // ID / UID (Event-ID)
            evt.uid = eventJson.optString("id", null);

            // Occurrence-spezifische ID (wichtig bei Serien/Vorkommen)
            evt.occurrenceId = occ.optString("id", null);

            // Kalender-Zuordnung
            evt.calendarId = calendar.id;

            // Titel / Beschreibung / Ort
            evt.summary = eventJson.optString("summary", "");
            evt.description = eventJson.optString("description", "");
            evt.location = eventJson.optString("location", "");

            // Startzeit
            JSONObject startJson = eventJson.optJSONObject("start");
            if (startJson != null) {
                evt.dtStartUtcMillis = parseUtcDateTime(startJson);
            }

            // Endzeit
            JSONObject endJson = eventJson.optJSONObject("end");
            if (endJson != null) {
                evt.dtEndUtcMillis = parseUtcDateTime(endJson);
            }

            // Letzte Änderung
            JSONObject lmJson = eventJson.optJSONObject("lastModificationTime");
            if (lmJson != null) {
                evt.lastModifiedUtcMillis = parseUtcDateTime(lmJson);
                // Kompatibilität: altes Feld spiegeln
                evt.lastModifiedUtc = evt.lastModifiedUtcMillis;
            }

            // Ganztägig?
            evt.allDay = eventJson.optBoolean("isAllDay", false);

            out.add(evt);
        }

        Log.i(TAG, "parseEventsResponse(): " + out.size() + " Events aus Occurrences.get gelesen.");
    }

    /**
     * Convenience-Overload für den SyncAdapter:
     * - nutzt intern mToken
     * - wenn sinceUtcMillis == 0: Standard-Zeitraum (Vergangenheit+Zukunft)
     */
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
     * Lädt alle Vorkommen (Occurrences) aus einem Kerio-Kalender in einem
     * gegebenen Zeitfenster.
     */
    public List<RemoteEvent> fetchEvents(String token,
                                         RemoteCalendar calendar,
                                         long startUtcMillis,
                                         long endUtcMillis)
            throws IOException, JSONException {

        List<RemoteEvent> events = new ArrayList<>();

        // ----------------- SearchQuery bauen -----------------
        JSONObject query = new JSONObject();

        // Felder, die wir zurückhaben wollen
        JSONArray fields = new JSONArray();
        fields.put("id");
        fields.put("summary");
        fields.put("description");
        fields.put("location");
        fields.put("start");
        fields.put("end");
        fields.put("lastModificationTime");
        fields.put("isAllDay");
        query.put("fields", fields);

        // Bedingungen (SearchQuery.conditions)
        JSONArray conditions = new JSONArray();

        // start >= startUtcMillis
        JSONObject condStart = new JSONObject();
        condStart.put("fieldName", "start");
        condStart.put("comparator", "GreaterEq");
        condStart.put("value", buildKerioUtcString(startUtcMillis));
        conditions.put(condStart);

        // end < endUtcMillis   (Kerio verlangt explizit LessThan für 'end')
        JSONObject condEnd = new JSONObject();
        condEnd.put("fieldName", "end");
        condEnd.put("comparator", "LessThan"); // <-- FIX: NICHT LessEq
        condEnd.put("value", buildKerioUtcString(endUtcMillis));
        conditions.put(condEnd);

        query.put("conditions", conditions);
        query.put("combining", "And");

        // Paging
        query.put("start", 0);
        query.put("limit", 1000);

        // Sortierung: nach Startzeit aufsteigend
        JSONArray orderBy = new JSONArray();
        JSONObject order = new JSONObject();
        order.put("columnName", "start");
        order.put("direction", "Asc");
        order.put("caseSensitive", false);
        orderBy.put(order);
        query.put("orderBy", orderBy);

        // --------------- Methoden-Parameter bauen ---------------
        JSONObject params = new JSONObject();
        params.put("token", token);
        params.put("folderIds", new JSONArray().put(calendar.id));
        params.put("query", query);

        JSONObject response = call("Occurrences.get", params, true);

        parseEventsResponse(response, calendar, events);

        return events;
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
        String respBody = null;

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

            JSONObject respJson;
            try {
                respJson = new JSONObject(respBody);
            } catch (JSONException e) {
                String snippet = respBody;
                if (snippet.length() > 600) {
                    snippet = snippet.substring(0, 600) + "...";
                }
                Log.e(TAG, "JSONException beim Verarbeiten der JSON-RPC-Antwort für Methode "
                        + method + ": " + e.getMessage()
                        + "\nAntwort-Body-Ausschnitt:\n" + snippet, e);
                throw e;
            }

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

    // ------------------------------------------------------------------------
    // Hilfsfunktionen
    // ------------------------------------------------------------------------

    private void ensureLoggedIn() throws IOException, JSONException {
        if (mToken == null) {
            Log.d(TAG, "Noch kein Token vorhanden – führe Session.login() aus.");
            login();
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

    // ------------------------------------------------------------------------
    // trust-all SSL (nur für Tests!)
    // ------------------------------------------------------------------------

    private static SSLSocketFactory getUnsafeSslSocketFactory() throws IOException {
        if (sUnsafeSslSocketFactory != null) {
            return sUnsafeSslSocketFactory;
        }

        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
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

    // ------------------------------------------------------------------------
    // Modellklassen + Zeit-Helfer
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

    /**
     * Repräsentiert ein Kalenderevent in Kerio.
     */
    public static class RemoteEvent {
        public String uid;                 // Event-ID
        public String occurrenceId;        // Occurrence-ID (Vorkommen-ID)
        public String calendarId;          // Folder-ID
        public String summary;
        public String description;
        public String location;
        public long dtStartUtcMillis;
        public long dtEndUtcMillis;
        public boolean allDay;

        public long lastModifiedUtcMillis; // neu: klare Benennung
        public long lastModifiedUtc;       // Kompatibilität (Spiegel)

        @Override
        public String toString() {
            return "RemoteEvent{" +
                    "uid='" + uid + '\'' +
                    ", occurrenceId='" + occurrenceId + '\'' +
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
     * Wandelt ein UtcDateTime JSON-Objekt in UTC-Millis um.
     *
     * Kerio liefert UtcDateTime so:
     * {
     * "date": "2024-11-12",
     * "time": "13:00:00",
     * "tz": "UTC"
     * }
     */
    private long parseUtcDateTime(JSONObject obj) throws JSONException {
        String date = obj.getString("date"); // YYYY-MM-DD
        String time = obj.getString("time"); // HH:mm:ss

        String combined = date + "T" + time + "Z"; // ISO8601

        return java.time.Instant.parse(combined).toEpochMilli();
    }

    /**
     * Wandelt einen UTC-Millis-Wert in das Kerio-UtcDateTime-Stringformat um:
     * Beispiel: 20251210T110355Z
     */
    private String buildKerioUtcString(long utcMillis) {
        java.time.Instant instant = java.time.Instant.ofEpochMilli(utcMillis);
        java.time.ZonedDateTime zdt = java.time.ZonedDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
        return String.format(
                java.util.Locale.US,
                "%04d%02d%02dT%02d%02d%02dZ",
                zdt.getYear(),
                zdt.getMonthValue(),
                zdt.getDayOfMonth(),
                zdt.getHour(),
                zdt.getMinute(),
                zdt.getSecond());
    }
}
