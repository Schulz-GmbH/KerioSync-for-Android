# KerioSync – Android Kerio Connect Kalender-Synchronisation

## Projektübersicht

KerioSync ist eine Android-Systemanwendung zur bidirektionalen Synchronisation
von Kalenderdaten zwischen Kerio Connect und dem Android-Kalendersystem.
Die App integriert sich tief in das Android-Account- und Sync-Framework
und arbeitet vollständig im Hintergrund ohne klassisches Launcher-Icon.

## Hauptfunktionen

- Eigener Android Account-Typ (de.schulz.keriosync.account)
- Server → Client Synchronisation (Kerio → Android)
- Client → Server Synchronisation (Android → Kerio)
- Unterstützung für persönliche, geteilte und öffentliche Kalender
- Korrekte Behandlung von ganztägigen und mehrtägigen Terminen
- Periodischer Sync über WorkManager
- Instant-Sync bei lokalen Kalenderänderungen
- SSL-Unterstützung (System-CA, Custom-CA, Trust-All für Debug)

## Architektur

KerioSync nutzt folgende Android-Komponenten:

- AccountManager (Systemintegration)
- AbstractAccountAuthenticator
- SyncAdapter (CalendarContract)
- WorkManager & JobScheduler
- Android CalendarProvider

## Wichtige Module

- auth/
  - KerioAccountAuthenticator
  - KerioAuthenticatorService
  - KerioAccountConstants

- sync/
  - KerioCalendarSyncAdapter
  - KerioCalendarSyncService
  - KerioSyncScheduler
  - KerioAccountMigrator
  - BootReceiver

- net/
  - KerioApiClient (JSON-RPC Client)

## Build-Voraussetzungen

- Android Studio (aktuell)
- JDK 11
- Android SDK:
  - minSdk 26
  - targetSdk 36
- Gradle Wrapper (enthalten)

## Projekt bauen

### Debug-Build

```bash
./gradlew assembleDebug
```

APK:
```
app/build/outputs/apk/debug/app-debug.apk
```

Installation:
```bash
adb install -r app-debug.apk
```

Hinweis:
Die Debug-Version enthält eine Launcher-Activity
(DebugLauncherActivity) für Android Studio.

### Release-Build

```bash
./gradlew assembleRelease
```

APK:
```
app/build/outputs/apk/release/app-release.apk
```

Die Release-Version besitzt kein Launcher-Icon
und ist ausschließlich über die Android-Kontoeinstellungen nutzbar.

## Installation & Nutzung

1. APK installieren
2. Android Einstellungen öffnen
3. Konten → Konto hinzufügen → Kerio Sync
4. Server-URL, Benutzername und Passwort eingeben
5. Kalender erscheinen automatisch in Google Kalender,
   Samsung Kalender und allen CalendarContract-kompatiblen Apps

## Sicherheit

- Trust-All-SSL ausschließlich für Debug-Zwecke verwenden
- Für Produktivbetrieb System-CA oder eigene Firmen-CA nutzen
- Zugangsdaten werden über den Android AccountManager gespeichert

## Logging

```bash
adb logcat | grep Kerio
```

Relevante Log-Tags:
- KerioCalendarSync
- KerioApiClient
- KerioSyncScheduler

## Einschränkungen

- Kontakte-Synchronisation aktuell nicht implementiert
- Abhängig von Kerio-Connect-JSON-RPC-Version

## Roadmap

- Kontakte-Sync (Server → Client)
- Aufgaben / Notes
- Erweiterte Konfliktbehandlung

## Lizenz

© Schulz GmbH – interne Entwicklung
