# CRUCIBLE – běhová zpráva (AVIOR Delta IV, versionCode 1013)

**Prostředí:** Android emulátor v CI GitHub Actions (reactivecircus/android-emulator-runner, API 34, x86_64,
google_apis, pixel_6, 4 GB RAM, KVM, qemu 37.2.13). **Emulátor ≠ telefon.** Na fyzickém telefonu nic z níže
uvedeného ověřeno nebylo. Mozek (LLM) v emulátorových testech nebyl stažen → cesty „s mozkem“ běžely bez
skutečného modelu; cesta „bez mozku“ je skutečný kód aplikace (žádný mock).

## Co bylo na emulátoru skutečně ověřeno
| Běh | Commit | Co proběhlo | Výsledek |
|---|---|---|---|
| #4 | f63ad33 | instrumentace: dotaz bez mozku i cloudu přes skutečnou UI/službu | **PROŠEL** (odpověď 575 ms) |
| #5 | cb5f2fd | smoke: instalace, 3× studený start | bez pádu aplikace; 6776 / 2672 / 2810 ms |
| #5 | cb5f2fd | smoke: teplý start | 716 ms (1 měření) |
| #7 | 23c2038 | kontrola: emulátor 180 s **bez** AVIORu | přežil |
| #7 | 23c2038 | smoke: studený start | 1637 / 1785 ms, pak emulátor zmizel |
| #8 | 0145df6 | kontrola bez aplikace 180 s, pak smoke bez Vulkanu | kontrola přežila; po startu AVIORu emulátor opět zmizel (~100 s) |

Pád **aplikace** (crash buffer, ANR, chyba JS) nebyl zachycen v žádném běhu, ve kterém zařízení žilo.

## Neověřeno (emulátor zmizel)
Otočení displeje, návrat po ukončení procesu, trim-memory, paměť (PSS), většina instrumentačních testů
(`CrucibleRuntimeTest`, `DbOnDeviceTest`). Výsledky „✅“ z běhu #5 po 13:37:55 byly **falešné** (skript kontroloval
prázdný logcat bez zařízení) – chyba skriptu opravena v 23c2038, viz CRUCIBLE_BUG_REPORT.md (C-01).

## Otevřený problém: emulátor (qemu) mizí 45–120 s po prvním spuštění AVIORu
- Opakovatelné: běhy #1, #4, #5, #7, #8 (všechny vyhodnocené běhy s aplikací).
- Bez aplikace přežil 180 s (běh #7) → spouštěčem je start aplikace.
- Umírá **hostitelský** proces qemu, ne aplikace; bez crash dumpu (`/tmp/android-runner/emu-crash-*.db` prázdný);
  RAM hostitele OK (avail ~9,6 GB), aplikace nemá velké assety (188 KB) ani trvalé animace v klidu.
- Vyloučeno: testovací platforma (padá i při smoke), dialog oprávnění (opraveno v #4), nedostatek RAM hostitele,
  `-gpu guest` (#5 nepomohl).
- Vulkan vyloučen: běh #8 s `-feature -Vulkan` dopadl stejně.
- **Nový nález (běh #8):** volné místo na disku hostitele bylo v klidu stabilní (76,5 GB), po instalaci a startu AVIORu
  kleslo za ~100 s na 68,5 GB; po zániku emulátoru se ~6,5 GB vrátilo. Něco tedy po startu masivně zapisuje.
  AVIOR nic nepředalokuje (žádné `setLength`/`fallocate`), vestavěné balíčky jsou malé, APK nemá velké assety.
  Kdo zapisuje (aplikace / systém v emulátoru / emulátor na hostiteli), měří běh 4ceb080 (monitor `/data`,
  data AVIORu přes `run-as`, velikost AVD) – **výsledek zatím není**.
- Aplikace v hostu nemůže legálně ukončit hostitelský proces; jde o chybu/limit emulátoru v CI spouštěnou
  zátěží, kterou AVIOR vytváří. Na telefonu tento mechanismus neexistuje – ale to je úvaha, ne měření.
