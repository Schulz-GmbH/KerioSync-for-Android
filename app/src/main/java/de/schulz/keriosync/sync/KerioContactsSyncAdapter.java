package de.schulz.keriosync.sync;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderOperation;
import android.content.ContentValues;
import android.content.ContentUris;
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

import de.schulz.keriosync.auth.KerioAccountConstants;
import de.schulz.keriosync.net.KerioApiClient;

/**
 * SyncAdapter für Kerio Kontakte (Server -> Client).
 *
 * Scope:
 * - Kontakte vom Kerio Server via Contacts.get lesen (personal + shared)
 * - Lokale RawContacts des Kerio Account-Typs upserten
 * - Upload (Client -> Server) ist deaktiviert
 *
 * Wichtige Änderung:
 * - SOURCE_ID ist jetzt <folderId>:<contactId>, damit Personal+Shared sauber
 * getrennt bleiben.
 * - folderId/folderName werden in RawContacts.SYNC1/SYNC2 hinterlegt.
 */
public class KerioContactsSyncAdapter extends AbstractThreadedSyncAdapter {

    private static final String TAG = "KerioContactsSyncAdapter";

    public KerioContactsSyncAdapter(Context context, boolean autoInitialize) {
        super(context, autoInitialize);
    }

    @Override
    public void onPerformSync(Account account, Bundle extras, String authority,
            android.content.ContentProviderClient provider, SyncResult syncResult) {

        Log.i(TAG, "onPerformSync(): account=" + account.name + ", authority=" + authority + ", extras=" + extras);

        if (!hasContactsPermissions()) {
            Log.w(TAG, "onPerformSync(): Fehlende Contacts-Permissions. Bitte in den App-Einstellungen erteilen.");
            syncResult.stats.numIoExceptions++;
            return;
        }

        // Sicherstellen, dass der Account in der Kontakte-App als sichtbare Quelle
        // aktiviert ist.
        // Ohne diese Settings können manche OEM-Apps (Samsung/Google Kontakte) die
        // Quelle ausblenden.
        ensureAccountVisibleInContacts(account);

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
            javax.net.ssl.SSLSocketFactory customFactory = null;
            if (!TextUtils.isEmpty(customCaUri)) {
                try {
                    customFactory = KerioSslHelper.loadCustomCaSocketFactory(getContext(), Uri.parse(customCaUri));
                } catch (Exception e) {
                    Log.e(TAG, "Custom CA konnte nicht geladen werden: " + e.getMessage(), e);
                }
            }

            client = new KerioApiClient(serverUrl, username, password, trustAll, customFactory);

            List<KerioApiClient.RemoteContact> remoteContacts = client.fetchContacts(null);

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
                Long rawContactId = localBySourceId.get(sourceId);

                if (rawContactId == null) {
                    rawContactId = insertRawContact(account, sourceId, rc);
                    if (rawContactId != null)
                        inserted++;
                } else {
                    updateRawContact(rawContactId, rc);
                    updated++;
                }
            }

            int deleted = 0;
            for (Map.Entry<String, Long> e : localBySourceId.entrySet()) {
                String sourceId = e.getKey();
                if (!remoteIds.contains(sourceId)) {
                    if (deleteRawContact(e.getValue()))
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

    /**
     * Stellt sicher, dass der Account als Kontakt-Quelle sichtbar ist.
     *
     * Hintergrund:
     * Einige Kontakte-Apps (insb. OEMs wie Samsung) verwenden
     * ContactsContract.Settings,
     * um zu entscheiden, ob eine Account-Quelle angezeigt wird.
     *
     * Wir setzen:
     * - UNGROUPED_VISIBLE = 1 (nicht gruppierte Kontakte anzeigen)
     * - SHOULD_SYNC = 1 (Sync aktiv)
     *
     * Wichtig: Als SyncAdapter (CALLER_IS_SYNCADAPTER=true), damit der
     * ContactsProvider
     * die Settings für diesen Account akzeptiert.
     *
     * @param account Kerio Account (AccountName + AccountType)
     */
    private void ensureAccountVisibleInContacts(Account account) {
        if (account == null) {
            return;
        }
        try {
            ContentValues values = new ContentValues();
            values.put(ContactsContract.Settings.ACCOUNT_NAME, account.name);
            values.put(ContactsContract.Settings.ACCOUNT_TYPE, account.type);
            values.put(ContactsContract.Settings.UNGROUPED_VISIBLE, 1);
            values.put(ContactsContract.Settings.SHOULD_SYNC, 1);

            Uri settingsUri = asSyncAdapter(ContactsContract.Settings.CONTENT_URI);

            int updated = 0;
            try {
                updated = getContext().getContentResolver().update(
                        settingsUri,
                        values,
                        ContactsContract.Settings.ACCOUNT_NAME + "=? AND " + ContactsContract.Settings.ACCOUNT_TYPE
                                + "=?",
                        new String[] { account.name, account.type });
            } catch (Exception e) {
                Log.w(TAG, "ensureAccountVisibleInContacts(): update() fehlgeschlagen: " + e.getMessage(), e);
            }

            if (updated <= 0) {
                try {
                    getContext().getContentResolver().insert(settingsUri, values);
                } catch (Exception e) {
                    Log.w(TAG, "ensureAccountVisibleInContacts(): insert() fehlgeschlagen: " + e.getMessage(), e);
                }
            }

            Log.i(TAG, "ensureAccountVisibleInContacts(): gesetzt für " + account.name);

        } catch (Exception e) {
            Log.w(TAG, "ensureAccountVisibleInContacts(): Fehler: " + e.getMessage(), e);
        }
    }

    private boolean hasContactsPermissions() {
        return ContextCompat.checkSelfPermission(getContext(),
                Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(getContext(),
                        Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED;
    }

    private Uri asSyncAdapter(Uri uri) {
        return uri.buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                .build();
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

    private Long insertRawContact(Account account, String sourceId, KerioApiClient.RemoteContact rc)
            throws RemoteException, OperationApplicationException {

        ArrayList<ContentProviderOperation> ops = new ArrayList<>();

        // 1) RawContact anlegen
        ops.add(ContentProviderOperation.newInsert(asSyncAdapter(ContactsContract.RawContacts.CONTENT_URI))
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, account.name)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, account.type)
                .withValue(ContactsContract.RawContacts.SOURCE_ID, sourceId)
                .withValue(ContactsContract.RawContacts.SYNC1, rc.folderId)
                .withValue(ContactsContract.RawContacts.SYNC2, rc.folderName)
                .build());

        // 2) Data Rows (BackReference auf RawContact #0)
        buildDataOpsForContact(ops, 0, rc);

        getContext().getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);

        // RawContact-ID erneut über SOURCE_ID nachschlagen (robust)
        Map<String, Long> after = loadLocalRawContacts(account);
        return after.get(sourceId);
    }

    private void updateRawContact(long rawContactId, KerioApiClient.RemoteContact rc)
            throws RemoteException, OperationApplicationException {

        ArrayList<ContentProviderOperation> ops = new ArrayList<>();

        // RawContact-Metadaten aktualisieren (Folder Info)
        ops.add(ContentProviderOperation.newUpdate(asSyncAdapter(ContactsContract.RawContacts.CONTENT_URI))
                .withSelection(ContactsContract.RawContacts._ID + "=?", new String[] { String.valueOf(rawContactId) })
                .withValue(ContactsContract.RawContacts.SYNC1, rc.folderId)
                .withValue(ContactsContract.RawContacts.SYNC2, rc.folderName)
                .build());

        // Bestehende Data Rows löschen (Name, Phones, Emails, ...)
        Uri dataUri = asSyncAdapter(ContactsContract.Data.CONTENT_URI);
        String sel = ContactsContract.Data.RAW_CONTACT_ID + "=?";
        String[] args = new String[] { String.valueOf(rawContactId) };
        ops.add(ContentProviderOperation.newDelete(dataUri).withSelection(sel, args).build());

        // Neue Data Rows hinzufügen (RawContactId fest)
        buildDataOpsForContact(ops, rawContactId, rc);

        getContext().getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
    }

    /**
     * Baut Data-Operationen (StructuredName, Emails, Phones) für einen Kontakt.
     *
     * @param ops               Liste, in die neue Ops geschrieben werden
     * @param rawContactRefOrId Entweder BackReference-Index (bei Insert) oder echte
     *                          RawContact-ID (bei Update)
     * @param rc                Remote-Kontakt
     */
    private void buildDataOpsForContact(ArrayList<ContentProviderOperation> ops, long rawContactRefOrId,
            KerioApiClient.RemoteContact rc) {

        // Helper: Setzen des RawContact-IDs (BackReference vs fix)
        ContentProviderOperation.Builder bName = ContentProviderOperation
                .newInsert(asSyncAdapter(ContactsContract.Data.CONTENT_URI))
                .withValue(ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE);

        if (rawContactRefOrId == 0) {
            bName.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0);
        } else {
            bName.withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactRefOrId);
        }

        // Namen setzen
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
                        .newInsert(asSyncAdapter(ContactsContract.Data.CONTENT_URI))
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
                        .newInsert(asSyncAdapter(ContactsContract.Data.CONTENT_URI))
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
    }

    private boolean deleteRawContact(long rawContactId) {
        try {
            Uri uri = android.content.ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI,
                    rawContactId);
            int rows = getContext().getContentResolver().delete(asSyncAdapter(uri), null, null);
            return rows > 0;
        } catch (Exception e) {
            Log.e(TAG, "deleteRawContact() Fehler: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Baut eine stabile, eindeutige SOURCE_ID für Android RawContacts.
     * Hintergrund: In Kerio können Kontakt-IDs in unterschiedlichen Adressbüchern
     * vorkommen.
     * Daher kombinieren wir folderId + contactId.
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
