# CRUCIBLE – změny

- 7af0ceb – běhové testy na skutečném Android emulátoru v CI (instrumentace + ADB smoke)
- 34704d9 – časové limity a diagnostika; běžící běh emulátoru se neruší
- fbcb69b – oprávnění notifikací (C-07), stav při vypršení, paměť hostitele
- f63ad33 – zrcadlo Maven pro Robolectric/Gradle (C-05), adb s limitem 60 s (C-06); 1012
- cb5f2fd – experiment: smoke před instrumentací, -gpu guest, crash data emulátoru
- 276230c – **1013**: místo přes StorageManager (C-02), REASON_EXCESSIVE_RESOURCE_USAGE (C-03), lint 0 chyb (C-04)
- 23c2038 – smoke bez falešných ✅ (C-01), kontrola emulátoru bez aplikace
- 0145df6 – experiment: emulátor bez Vulkanu, disk hostitele v monitoru
