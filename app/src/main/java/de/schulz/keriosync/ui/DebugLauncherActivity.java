package de.schulz.keriosync.ui;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Debug-only Launcher Activity.
 * Wird nur im Debug-Variant eingebunden, um Android Studio Start zu ermöglichen,
 * ohne im Release ein Launcher-Icon zu haben.
 */
public class DebugLauncherActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Öffne direkt die eigentliche Account-Settings UI
        Intent intent = new Intent(this, AccountSettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);

        // Direkt wieder schließen
        finish();
    }
}
