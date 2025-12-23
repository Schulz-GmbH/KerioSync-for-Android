## [v0.9.5] – 2025-12-23

### Added

- Launcher-freier Betrieb der App (kein App-Icon in der App-Übersicht)
- Debug-spezifische Launcher-Activity für komfortables Entwickeln in Android Studio
- Vollständig korrektes Adaptive App Icon (API 26+)
  - Foreground/Background sauber getrennt
  - Angepasste Skalierung für systemkonforme Darstellung
- Saubere Systemintegration über Android Account Settings

### Fixed

- Behebung von Resource-Linking-Fehlern durch doppelte Drawable-Namen
- Korrekte Trennung von Adaptive-Icon-Ressourcen (`mipmap-anydpi-v26`)
- Verhindert fehlerhafte Manifest-Einträge (`adaptive-icon` fälschlich im Manifest)
- Stabiler Build bei mehrfachen Resource-Merges (PNG vs. XML)
- Sicherstellung der Existenz von `@string/app_name` für AccountAuthenticator

---

## [v0.9.4] – 2025-12-23

### Added

- Unterstützung für geteilte, freigegebene und delegierte Kerio-Kalender
- Automatische Einbindung abonnierter Shared Mailboxes und Public Calendars

### Fixed

- Verhindert mehrfaches Anlegen identischer Kalender bei wiederholten Syncs
- Respektiert Benutzer-Einstellungen für:
  - Kalenderfarbe
  - Sichtbarkeit (ein-/ausgeblendet)
  - Synchronisations-Schalter (SYNC_EVENTS)
- Saubere Behandlung von durch das System abgebrochenen Syncs (Thread Interrupts)
- Vermeidung paralleler Sync-Läufe durch prozessweiten Single-Run-Guard
- Stabiler Abgleich lokaler und entfernter Kalender über `_SYNC_ID`

---

## [v0.9.3] – 2025-12-23

### Fixed

- Korrekte Behandlung ganztägiger und mehrtägiger Termine bei der Synchronisation
- Behebung eines Off-by-one-Fehlers (-1 / +1 Tag) durch unterschiedliche Enddatum-Semantik
  zwischen Kerio Connect (inklusives Enddatum) und Android CalendarContract (exklusives DTEND)
- Unterstützung von Kerio Date-only Werten (`yyyyMMdd`) ohne DateTimeParseException
- Verhindert das Verschwinden von Terminen nach Client-seitigen Änderungen
- Stabile Round-Trip-Synchronisation für ganztägige Termine (Android ↔ Kerio)
