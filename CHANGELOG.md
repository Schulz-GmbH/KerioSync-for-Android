---

## [v0.9.3] – 2025-12-23

### Fixed

- Korrekte Behandlung ganztägiger und mehrtägiger Termine bei der Synchronisation
- Behebung eines Off-by-one-Fehlers (-1 / +1 Tag) durch unterschiedliche Enddatum-Semantik
  zwischen Kerio Connect (inklusives Enddatum) und Android CalendarContract (exklusives DTEND)
- Unterstützung von Kerio Date-only Werten (`yyyyMMdd`) ohne DateTimeParseException
- Verhindert das Verschwinden von Terminen nach Client-seitigen Änderungen
- Stabile Round-Trip-Synchronisation für ganztägige Termine (Android ↔ Kerio)
