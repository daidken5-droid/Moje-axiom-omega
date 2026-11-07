# ROLLBACK_GUIDE

1. Před aktualizací: Nastavení → Záloha (lokální soubor) nebo šifrovaná cloudová záloha.
2. Návrat: Android nedovolí nainstalovat nižší versionCode přes vyšší – starou verzi (např. 1015) nainstaluj po odinstalaci,
   pak obnov zálohu (Nastavení → Obnovit). Databáze 1016 nemění schéma (v4) → obnova do 1015 je kompatibilní.
3. Kód: `git checkout 058c8cd` (stav před REBIRTH) nebo tag `checkpoint-pre-hyperforge` (stav před HYPERFORGE).
