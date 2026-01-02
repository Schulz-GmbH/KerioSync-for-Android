package de.schulz.keriosync.sync;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.content.SyncResult;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLSocketFactory;

import de.schulz.keriosync.auth.KerioAccountConstants;
import de.schulz.keriosync.net.KerioApiClient;

/**
 * SyncAdapter für Kerio Kontakte -> Android ContactsProvider.
 *
 * Ziel:
 * - Kerio Addressbooks/Ordner als Kontakte-Gruppen abbilden
 * - Kontakte als RawContacts + Data Rows speichern
 * - Sichtbarkeit in OEM Kontakte-Apps (Samsung) sicherstellen
 *
 * Wichtig:
 * - In res/xml/sync_contacts.xml muss
 * android:contentAuthority="com.android.contacts" stehen
 * - Im Manifest muss der Sync-Service android.permission.BIND_SYNC_ADAPTER
 * haben
 * - Optional (aber wichtig bei OEMs): android.provider.CONTACTS_STRUCTURE
 * meta-data
 * damit die Kontakte-App Feld-Strukturen kennt.
 */
public class KerioContactsSyncAdapter extends AbstractThreadedSyncAdapter {

    private static final String TAG = "KerioContactsSyncAdapter";

    private final Context mContext;
    private final ContentResolver mContentResolver;

    public KerioContactsSyncAdapter(Context context, boolean autoInitialize) {
        super(context, autoInitialize);
        this.mContext = context;
        this.mContentResolver = context.getContentResolver();
    }

    @Override
    public void onPerformSync(Account account, Bundle extras, String authority, ContentProviderClient provider,
            SyncResult syncResult) {

        Log.i(TAG, "onPerformSync(): account=" + (account != null ? account.name : "null")
                + ", authority=" + authority + ", extras=" + extras);

        if (account == null) {
            Log.e(TAG, "onPerformSync(): account ist NULL");
            return;
        }

        if (!hasContactsPermissions()) {
            Log.w(TAG, "onPerformSync(): fehlende Kontakte-Permissions (READ/WRITE_CONTACTS). Abbruch.");
            syncResult.stats.numAuthExceptions++;
            return;
        }

        AccountManager am = AccountManager.get(getContext());

        String serverUrl = am.getUserData(account, KerioAccountConstants.KEY_SERVER_URL);
        String username = am.getUserData(account, KerioAccountConstants.KEY_USERNAME);
        String password = am.getPassword(account);

        // Fallback: In unserer App ist der Account-Name der Benutzername.
        if (TextUtils.isEmpty(username)) {
            username = account.name;
        }

        // In deinem Projekt heißen die Keys so:
        String trustAllStr = am.getUserData(account, KerioAccountConstants.KEY_SSL_TRUST_ALL);
        boolean trustAll = "1".equals(trustAllStr);

        String customCaUri = am.getUserData(account, KerioAccountConstants.KEY_SSL_CUSTOM_CA_URI);

        if (TextUtils.isEmpty(serverUrl) || TextUtils.isEmpty(username) || TextUtils.isEmpty(password)) {
            Log.e(TAG, "onPerformSync(): Account-Settings unvollständig (serverUrl/username/password).");
            syncResult.stats.numAuthExceptions++;
            return;
        }

        KerioApiClient client = null;
        try {
            SSLSocketFactory customFactory = null;
            if (!TextUtils.isEmpty(customCaUri)) {
                try {
                    customFactory = KerioSslHelper.loadCustomCaSocketFactory(getContext(), Uri.parse(customCaUri));
                } catch (Exception e) {
                    Log.e(TAG, "Custom CA konnte nicht geladen werden: " + e.getMessage(), e);
                }
            }

            client = new KerioApiClient(serverUrl, username, password, trustAll, customFactory);

            List<KerioApiClient.RemoteContact> remoteContacts = client.fetchContacts(null);

            // Stelle sicher, dass der Account in der Kontakte-App als eigener Filter/Quelle
            // erscheint.
            ensureAccountSettingsVisible(account);

            // Adressbücher/Ordner als "Gruppen" abbilden
            Map<String, Long> groupRowIdByFolderId = ensureGroups(account, remoteContacts);

            Map<String, Long> localBySourceId = loadLocalRawContacts(account);

            HashSet<String> remoteIds = new HashSet<>();
            for (KerioApiClient.RemoteContact rc : remoteContacts) {
                if (rc != null && !TextUtils.isEmpty(rc.id)) {
                    remoteIds.add(buildSourceId(rc));
                }
            }

            int inserted = 0;
            int updated = 0;

            for (KerioApiClient.RemoteContact rc : remoteContacts) {
                if (rc == null || TextUtils.isEmpty(rc.id))
                    continue;

                String sourceId = buildSourceId(rc);
                Long groupRowId = (groupRowIdByFolderId != null && rc.folderId != null)
                        ? groupRowIdByFolderId.get(rc.folderId)
                        : null;

                Long rawContactId = localBySourceId.get(sourceId);

                if (rawContactId == null) {
                    rawContactId = insertRawContact(account, sourceId, rc, groupRowId);
                    if (rawContactId != null)
                        inserted++;
                } else {
                    updateRawContact(account, rawContactId, rc, groupRowId);
                    updated++;
                }
            }

            int deleted = 0;
            for (Map.Entry<String, Long> e : localBySourceId.entrySet()) {
                String sourceId = e.getKey();
                if (!remoteIds.contains(sourceId)) {
                    if (deleteRawContact(account, e.getValue()))
                        deleted++;
                }
            }

            Log.i(TAG,
                    "onPerformSync(): contacts inserted=" + inserted + " updated=" + updated + " deleted=" + deleted);

        } catch (IOException ioe) {
            Log.e(TAG, "onPerformSync(): IO Fehler: " + ioe.getMessage(), ioe);
            syncResult.stats.numIoExceptions++;
        } catch (JSONException je) {
            Log.e(TAG, "onPerformSync(): JSON Fehler: " + je.getMessage(), je);
            syncResult.stats.numParseExceptions++;
        } catch (Exception e) {
            Log.e(TAG, "onPerformSync(): Fehler: " + e.getMessage(), e);
            syncResult.stats.numIoExceptions++;
        } finally {
            if (client != null) {
                try {
                    client.logout();
                } catch (Exception ignore) {
                }
            }
        }
    }

    private boolean hasContactsPermissions() {
        return ContextCompat.checkSelfPermission(getContext(),
                Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(getContext(),
                        Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED;
    }

    private Uri asSyncAdapter(Uri uri) {
        return asSyncAdapter(uri, null);
    }

    /**
     * Erzeugt eine SyncAdapter-URI und setzt zusätzlich Account-Query-Parameter.
     *
     * Hintergrund:
     * Einige Provider-Implementierungen (v.a. OEMs wie Samsung) verhalten sich je
     * nach Query-Parametern unterschiedlich.
     * Wenn ACCOUNT_NAME/ACCOUNT_TYPE fehlen, werden u.U. Gruppen/Settings nicht
     * korrekt dem Account zugeordnet oder die
     * Anzeige/Filterung in der Kontakte-App ist inkonsistent.
     */
    private Uri asSyncAdapter(Uri uri, Account account) {
        Uri.Builder b = uri.buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true");

        if (account != null) {
            b.appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, account.name);
            b.appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE, account.type);
        }

        return b.build();
    }

    /**
     * Stellt sicher, dass der Account in
     * {@link android.provider.ContactsContract.Settings}
     * als sichtbar markiert ist.
     *
     * Hintergrund (Samsung/OEM):
     * Viele Kontakte-Apps bauen den Quellen-Filter ("Alle Kontakte / Telefon /
     * Google / ...")
     * unter anderem aus ContactsContract.Settings. Fehlt dort ein Eintrag mit
     * UNGROUPED_VISIBLE=1, wird der Account zwar synchronisiert, aber nicht als
     * Quelle
     * zur Auswahl angeboten.
     *
     * WICHTIG:
     * - Die Kombination (ACCOUNT_NAME, ACCOUNT_TYPE, DATA_SET) ist eindeutig.
     * - Wir arbeiten immer mit DATA_SET=NULL, damit keine "wachsenden" Duplikate
     * entstehen.
     */
    private void ensureAccountSettingsVisible(Account account) {
        ContentResolver resolver = mContext.getContentResolver();

        String accountName = account.name;
        String accountType = account.type;

        String selection = ContactsContract.Settings.ACCOUNT_NAME + "=? AND "
                + ContactsContract.Settings.ACCOUNT_TYPE + "=? AND "
                + ContactsContract.Settings.DATA_SET + " IS NULL";

        String[] selectionArgs = new String[] { accountName, accountType };

        Cursor c = null;

        try {
            c = resolver.query(
                    ContactsContract.Settings.CONTENT_URI,
                    new String[] {
                            ContactsContract.Settings.UNGROUPED_VISIBLE,
                            ContactsContract.Settings.SHOULD_SYNC
                    },
                    selection,
                    selectionArgs,
                    null);

            ContentValues values = new ContentValues();
            values.put(ContactsContract.Settings.UNGROUPED_VISIBLE, 1);
            values.put(ContactsContract.Settings.SHOULD_SYNC, 1);

            if (c != null && c.moveToFirst()) {
                int oldVisible = c.getInt(0);
                int oldSync = c.getInt(1);

                resolver.update(ContactsContract.Settings.CONTENT_URI, values, selection, selectionArgs);

                Log.i(TAG, "ensureAccountSettingsVisible(): Settings aktualisiert für "
                        + accountName + " (DATA_SET=NULL, UNGROUPED_VISIBLE=" + oldVisible + "->1, SHOULD_SYNC="
                        + oldSync + "->1)");
            } else {
                // Insert nur, wenn wirklich keiner existiert. (DATA_SET bleibt NULL)
                values.put(ContactsContract.Settings.ACCOUNT_NAME, accountName);
                values.put(ContactsContract.Settings.ACCOUNT_TYPE, accountType);

                resolver.insert(ContactsContract.Settings.CONTENT_URI, values);

                Log.i(TAG, "ensureAccountSettingsVisible(): Settings neu angelegt für "
                        + accountName + " (DATA_SET=NULL, UNGROUPED_VISIBLE=1, SHOULD_SYNC=1)");
            }
        } catch (Exception e) {
            Log.w(TAG, "ensureAccountSettingsVisible(): Fehler: " + e.getMessage(), e);
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    /**
     * Erstellt/aktualisiert {@link ContactsContract.Groups} für die auf dem Server
     * vorhandenen Kontakt-Ordner (Adressbücher).
     *
     * @return Map folderId -> groupRowId
     */
    private Map<String, Long> ensureGroups(Account account, List<KerioApiClient.RemoteContact> remoteContacts) {
        Map<String, String> folderNames = new HashMap<>();
        if (remoteContacts != null) {
            for (KerioApiClient.RemoteContact rc : remoteContacts) {
                if (rc == null)
                    continue;
                if (TextUtils.isEmpty(rc.folderId))
                    continue;
                String name = (rc.folderName != null && !rc.folderName.trim().isEmpty())
                        ? rc.folderName.trim()
                        : rc.folderId;
                folderNames.put(rc.folderId, name);
            }
        }

        Map<String, Long> out = new HashMap<>();
        if (folderNames.isEmpty())
            return out;

        Cursor c = null;
        try {
            Uri groupsUri = asSyncAdapter(ContactsContract.Groups.CONTENT_URI, account);

            String[] proj = new String[] {
                    ContactsContract.Groups._ID,
                    ContactsContract.Groups.SOURCE_ID,
                    ContactsContract.Groups.TITLE
            };

            String sel = ContactsContract.Groups.ACCOUNT_NAME + "=? AND " +
                    ContactsContract.Groups.ACCOUNT_TYPE + "=? AND " +
                    ContactsContract.Groups.SOURCE_ID + " IS NOT NULL AND " +
                    ContactsContract.Groups.DATA_SET + " IS NULL";

            String[] args = new String[] { account.name, account.type };

            c = getContext().getContentResolver().query(groupsUri, proj, sel, args, null);

            Map<String, Long> existingBySource = new HashMap<>();
            Map<String, String> existingTitle = new HashMap<>();

            if (c != null) {
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    String sourceId = c.getString(1);
                    String title = c.getString(2);
                    if (!TextUtils.isEmpty(sourceId)) {
                        existingBySource.put(sourceId, id);
                        existingTitle.put(sourceId, title);
                    }
                }
            }

            for (Map.Entry<String, String> e : folderNames.entrySet()) {
                String folderId = e.getKey();
                String title = e.getValue();

                Long existingId = existingBySource.get(folderId);
                if (existingId == null) {
                    ContentValues cv = new ContentValues();
                    cv.put(ContactsContract.Groups.ACCOUNT_NAME, account.name);
                    cv.put(ContactsContract.Groups.ACCOUNT_TYPE, account.type);
                    cv.put(ContactsContract.Groups.SOURCE_ID, folderId);
                    cv.putNull(ContactsContract.Groups.DATA_SET);

                    // Optional: SYSTEM_ID stabil setzen, hilft manchen OEMs bei Anzeige
                    cv.put(ContactsContract.Groups.SYSTEM_ID, folderId);

                    cv.put(ContactsContract.Groups.TITLE, title);
                    cv.put(ContactsContract.Groups.GROUP_VISIBLE, 1);

                    Uri inserted = getContext().getContentResolver().insert(groupsUri, cv);
                    long newId = inserted != null ? ContentUris.parseId(inserted) : -1;
                    if (newId > 0) {
                        out.put(folderId, newId);
                        Log.i(TAG, "ensureGroups(): Gruppe erstellt: " + title + " (" + folderId + "), id=" + newId);
                    }
                } else {
                    out.put(folderId, existingId);

                    String oldTitle = existingTitle.get(folderId);
                    if (oldTitle == null)
                        oldTitle = "";

                    if (!oldTitle.equals(title)) {
                        ContentValues cv = new ContentValues();
                        cv.put(ContactsContract.Groups.TITLE, title);
                        cv.put(ContactsContract.Groups.GROUP_VISIBLE, 1);
                        Uri u = ContentUris.withAppendedId(groupsUri, existingId);
                        getContext().getContentResolver().update(u, cv, null, null);

                        Log.i(TAG, "ensureGroups(): Gruppe umbenannt: " + oldTitle + " -> " + title + " (" + folderId
                                + ")");
                    }
                }
            }

        } catch (Exception e) {
            Log.w(TAG, "ensureGroups(): Fehler: " + e.getMessage(), e);
        } finally {
            if (c != null)
                c.close();
        }

        return out;
    }

    private Map<String, Long> loadLocalRawContacts(Account account) {
        Map<String, Long> map = new HashMap<>();

        Cursor c = null;
        try {
            String[] proj = new String[] {
                    ContactsContract.RawContacts._ID,
                    ContactsContract.RawContacts.SOURCE_ID,
                    ContactsContract.RawContacts.DELETED
            };

            String sel = ContactsContract.RawContacts.ACCOUNT_NAME + "=? AND " +
                    ContactsContract.RawContacts.ACCOUNT_TYPE + "=? AND " +
                    ContactsContract.RawContacts.SOURCE_ID + " IS NOT NULL";

            String[] args = new String[] { account.name, account.type };

            c = getContext().getContentResolver().query(
                    ContactsContract.RawContacts.CONTENT_URI,
                    proj,
                    sel,
                    args,
                    null);

            if (c != null) {
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    String sourceId = c.getString(1);
                    int deleted = c.getInt(2);

                    if (!TextUtils.isEmpty(sourceId) && deleted == 0) {
                        map.put(sourceId, id);
                    }
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "loadLocalRawContacts() Fehler: " + e.getMessage(), e);
        } finally {
            if (c != null)
                c.close();
        }

        Log.i(TAG, "loadLocalRawContacts(): found=" + map.size());
        return map;
    }

    private Long insertRawContact(Account account, String sourceId, KerioApiClient.RemoteContact rc, Long groupRowId)
            throws RemoteException, OperationApplicationException {

        ArrayList<ContentProviderOperation> ops = new ArrayList<>();

        // 1) RawContact anlegen
        ops.add(ContentProviderOperation.newInsert(asSyncAdapter(ContactsContract.RawContacts.CONTENT_URI, account))
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, account.name)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, account.type)
                .withValue(ContactsContract.RawContacts.SOURCE_ID, sourceId)
                .withValue(ContactsContract.RawContacts.SYNC1, rc.folderId)
                .withValue(ContactsContract.RawContacts.SYNC2, rc.folderName)
                .build());

        // 2) Data Rows (BackReference auf RawContact #0)
        buildDataOpsForContact(account, ops, 0, rc, groupRowId);

        getContext().getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);

        // RawContact-ID erneut über SOURCE_ID nachschlagen (robust)
        Map<String, Long> after = loadLocalRawContacts(account);
        return after.get(sourceId);
    }

    private void updateRawContact(Account account, long rawContactId, KerioApiClient.RemoteContact rc, Long groupRowId)
            throws RemoteException, OperationApplicationException {

        ArrayList<ContentProviderOperation> ops = new ArrayList<>();

        // RawContact-Metadaten aktualisieren (Folder Info)
        ops.add(ContentProviderOperation.newUpdate(asSyncAdapter(ContactsContract.RawContacts.CONTENT_URI, account))
                .withSelection(ContactsContract.RawContacts._ID + "=?", new String[] { String.valueOf(rawContactId) })
                .withValue(ContactsContract.RawContacts.SYNC1, rc.folderId)
                .withValue(ContactsContract.RawContacts.SYNC2, rc.folderName)
                .build());

        // Bestehende Data Rows löschen (Name, Phones, Emails, ...)
        Uri dataUri = asSyncAdapter(ContactsContract.Data.CONTENT_URI, account);
        String sel = ContactsContract.Data.RAW_CONTACT_ID + "=?";
        String[] args = new String[] { String.valueOf(rawContactId) };
        ops.add(ContentProviderOperation.newDelete(dataUri).withSelection(sel, args).build());

        // Neue Data Rows hinzufügen
        buildDataOpsForContact(account, ops, rawContactId, rc, groupRowId);

        getContext().getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
    }

    /**
     * Baut Data-Operationen (StructuredName, Emails, Phones, GroupMembership) für
     * einen Kontakt.
     *
     * @param ops               Liste, in die neue Ops geschrieben werden
     * @param rawContactRefOrId Entweder BackReference-Index (bei Insert) oder echte
     *                          RawContact-ID (bei Update)
     * @param rc                Remote-Kontakt
     */
    private void buildDataOpsForContact(Account account,
            ArrayList<ContentProviderOperation> ops,
            long rawContactRefOrId,
            KerioApiClient.RemoteContact rc,
            Long groupRowId) {

        ContentProviderOperation.Builder bName = ContentProviderOperation
                .newInsert(asSyncAdapter(ContactsContract.Data.CONTENT_URI, account))
                .withValue(ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE);

        if (rawContactRefOrId == 0) {
            bName.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0);
        } else {
            bName.withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactRefOrId);
        }

        String given = safe(rc.firstName);
        String family = safe(rc.surName);
        String middle = safe(rc.middleName);
        String display = safe(rc.commonName);

        if (!TextUtils.isEmpty(display)) {
            bName.withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, display);
        }
        if (!TextUtils.isEmpty(given)) {
            bName.withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, given);
        }
        if (!TextUtils.isEmpty(middle)) {
            bName.withValue(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME, middle);
        }
        if (!TextUtils.isEmpty(family)) {
            bName.withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, family);
        }

        ops.add(bName.build());

        // Emails
        if (rc.emails != null) {
            for (String email : rc.emails) {
                if (TextUtils.isEmpty(email))
                    continue;

                ContentProviderOperation.Builder bEmail = ContentProviderOperation
                        .newInsert(asSyncAdapter(ContactsContract.Data.CONTENT_URI, account))
                        .withValue(ContactsContract.Data.MIMETYPE,
                                ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                        .withValue(ContactsContract.CommonDataKinds.Email.TYPE,
                                ContactsContract.CommonDataKinds.Email.TYPE_OTHER);

                if (rawContactRefOrId == 0) {
                    bEmail.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0);
                } else {
                    bEmail.withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactRefOrId);
                }

                ops.add(bEmail.build());
            }
        }

        // Phones
        if (rc.phones != null) {
            for (String phone : rc.phones) {
                if (TextUtils.isEmpty(phone))
                    continue;

                ContentProviderOperation.Builder bPhone = ContentProviderOperation
                        .newInsert(asSyncAdapter(ContactsContract.Data.CONTENT_URI, account))
                        .withValue(ContactsContract.Data.MIMETYPE,
                                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                        .withValue(ContactsContract.CommonDataKinds.Phone.TYPE,
                                ContactsContract.CommonDataKinds.Phone.TYPE_OTHER);

                if (rawContactRefOrId == 0) {
                    bPhone.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0);
                } else {
                    bPhone.withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactRefOrId);
                }

                ops.add(bPhone.build());
            }
        }

        // Gruppen-Zuordnung (Adressbuch/Ordner -> ContactsContract.Groups)
        if (groupRowId != null && groupRowId > 0) {
            ContentProviderOperation.Builder bGroup = ContentProviderOperation
                    .newInsert(asSyncAdapter(ContactsContract.Data.CONTENT_URI, account))
                    .withValue(ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID, groupRowId);

            if (rawContactRefOrId == 0) {
                bGroup.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0);
            } else {
                bGroup.withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactRefOrId);
            }

            ops.add(bGroup.build());
        }
    }

    private boolean deleteRawContact(Account account, long rawContactId) {
        try {
            Uri uri = ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawContactId);
            int rows = getContext().getContentResolver().delete(asSyncAdapter(uri, account), null, null);
            return rows > 0;
        } catch (Exception e) {
            Log.e(TAG, "deleteRawContact() Fehler: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Baut eine stabile, eindeutige SOURCE_ID für Android RawContacts.
     *
     * Format: <folderId>:<contactId>
     */
    private String buildSourceId(KerioApiClient.RemoteContact rc) {
        String fid = (rc != null) ? rc.folderId : null;
        String cid = (rc != null) ? rc.id : null;
        if (fid == null)
            fid = "";
        if (cid == null)
            cid = "";
        return fid + ":" + cid;
    }

    private String safe(String s) {
        return (s == null) ? "" : s.trim();
    }
}
