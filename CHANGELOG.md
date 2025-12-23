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
