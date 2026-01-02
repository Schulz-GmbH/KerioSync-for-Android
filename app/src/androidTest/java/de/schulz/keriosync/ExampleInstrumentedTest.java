package de.schulz.keriosync;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/**
 * @file ExampleInstrumentedTest.java
 * @brief Instrumentierter Test für die KerioSync Android-Anwendung
 * 
 *        Diese Klasse enthält instrumentierte Tests, die auf einem echten
 *        Android-Gerät
 *        oder Emulator ausgeführt werden. Sie dienen dazu, die grundlegende
 *        Funktionalität
 *        der Anwendung im Android-Kontext zu überprüfen.
 * 
 * @author Simon Marcel Linden
 * @date 2026
 * @see <a href="http://d.android.com/tools/testing">Android Testing
 *      Dokumentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class ExampleInstrumentedTest {

    /**
     * @brief Testet den Anwendungskontext
     * 
     *        Dieser Test überprüft, ob der Anwendungskontext korrekt initialisiert
     *        ist
     *        und ob die Paket-ID der Anwendung mit der erwarteten APPLICATION_ID
     *        aus der BuildConfig übereinstimmt.
     * 
     * @test Vergleicht die tatsächliche Paket-ID mit der erwarteten APPLICATION_ID
     * @throws AssertionError wenn die Paket-IDs nicht übereinstimmen
     */
    @Test
    public void useAppContext() {
        // Kontext der zu testenden Anwendung abrufen
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

        // Überprüfen, ob die Paket-ID mit der erwarteten ID übereinstimmt
        assertEquals(BuildConfig.APPLICATION_ID, appContext.getPackageName());
    }
}
