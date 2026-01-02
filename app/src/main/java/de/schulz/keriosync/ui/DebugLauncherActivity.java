/**
 * @file DebugLauncherActivity.java
 * @brief Debug-only Launcher Activity für Android Studio Start
 * @author Simon Schulz
 * @date 2. Januar 2026
 * @version 1.0
 *
 * Diese Activity wird nur im Debug-Build-Variant eingebunden und ermöglicht es,
 * die App direkt aus Android Studio zu starten, ohne dass ein Launcher-Icon
 * im Release-Build vorhanden sein muss.
 *
 * Workflow:
 * - Wird als LAUNCHER-Activity im Debug-Manifest konfiguriert
 * - onCreate(): Startet AccountSettingsActivity und beendet sich selbst
 * - Dient als Trampoline/Proxy für Debug-Zwecke
 */

package de.schulz.keriosync.ui;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * @class DebugLauncherActivity
 * @brief Debug-only Launcher Activity für Android Studio Start
 *        Diese Activity wird nur im Debug-Build-Variant eingebunden und
 *        ermöglicht es,
 *        die App direkt aus Android Studio zu starten, ohne dass ein
 *        Launcher-Icon
 *        im Release-Build vorhanden sein muss.
 *
 *        Workflow:
 *        - Wird als LAUNCHER-Activity im Debug-Manifest konfiguriert
 *        - onCreate(): Startet AccountSettingsActivity und beendet sich selbst
 *        - Dient als Trampoline/Proxy für Debug-Zwecke
 */
public class DebugLauncherActivity extends AppCompatActivity {

    /**
     * @brief Startet die AccountSettingsActivity und beendet sich selbst
     *        Dieser Lifecycle-Callback:
     *        1. Erstellt Intent für AccountSettingsActivity
     *        2. Setzt Flags (NEW_TASK | CLEAR_TOP) für korrektes
     *        Activity-Stack-Management
     *        3. Startet AccountSettingsActivity
     *        4. Beendet diese Debug-Launcher-Activity
     *
     *        Resultat: Benutzer sieht direkt AccountSettingsActivity ohne
     *        zwischenschritt.
     *
     * @param savedInstanceState Bundle mit gespeicherten Activity-Zustand (wird
     *                           ignoriert)
     */
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
