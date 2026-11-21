# VEGA na Google Play – připravenost (fáze F, 21. 11. 2026)

Ověřeno sestavením a rozborem APK/AAB na hostiteli. Odeslání do Play Console a kontrola Googlem: NEPROVEDENO (není přístup).

## Nálezy a opravy

| # | Nález | Dopad na Play | Oprava | Ověření |
|---|---|---|---|---|
| 1 | `targetSdk = 35` | **Blokuje aktualizace** – od 31. 8. 2026 musí nové aplikace i aktualizace cílit na Android 16 (API 36) | `compileSdk`/`targetSdk = 36` (jen `vega/build.gradle.kts`; AGP a AVIOR beze změny); CI doinstaluje platformu 36 | `aapt2 dump badging` → `targetSdkVersion 36`; testy zelené |
| 2 | `REQUEST_INSTALL_PACKAGES` + instalace APK samoúpravy | Zásady Play: aplikace z Play se nesmí aktualizovat mimo Play; oprávnění je omezené → zamítnutí | nový build **`play`** (`src/play/AndroidManifest.xml` odebere oprávnění, `BuildConfig.SELF_INSTALL=false` → APK se nestahuje ani neinstaluje, samoúprava jen připraví změnu na GitHubu) | `aapt2 dump permissions`: play bez oprávnění, release s ním |
| 3 | Poloha pro WiFi sken bez vysvětlení předem | Zásady o citlivých oprávněních vyžadují srozumitelné upozornění | panel „Proč WiFi potřebuje polohu“ před systémovým dialogem (jednou) | Chromium / čtení kódu; telefon NEOVĚŘENO |
| 4 | Diktování v Bez sítě mohlo jít přes online rozpoznávání | soulad s deklarací „bez sítě“ | jen ověřené místní rozpoznávání (fáze E) | čtení kódu, build |
| 5 | AAB pro Play nevznikal | nahrávání do Play vyžaduje AAB | krok CI „Balíček pro Google Play (AAB)“ → artefakt `VEGA-PLAY-AAB` (samostatný, nesmí zablokovat APK) | lokálně `:vega:bundlePlay` → 20,8 MB |

Podpis: beze změny (stejný klíč jako release; pro Play App Signing slouží jako upload key). applicationId `cz.axiom.vega` beze změny.

## Oprávnění a zdůvodnění (pro formulář v Play Console)

| Oprávnění | Proč | Kdy se žádá |
|---|---|---|
| INTERNET, ACCESS_NETWORK_STATE | volitelné online modely, rešerše, kurzy ČNB; vypne se režimem Bez sítě | instalace |
| NFC (+ HCE služba) | čtení/zápis vlastních NFC štítků, virtuální karta VEGA (vlastní AID, ne platební/přístupové karty) | instalace; použití jen na pokyn |
| ACCESS_WIFI_STATE, ACCESS_FINE_LOCATION | Android vydá seznam okolních WiFi jen s polohou; poloha se nezjišťuje ani neodesílá | až při „Sítě v okolí“, po vysvětlení |
| BLUETOOTH_SCAN (`neverForLocation`), BLUETOOTH_CONNECT | hledání vlastních BLE zařízení | až při hledání |
| REQUEST_INSTALL_PACKAGES | **jen přímá distribuce** (APK z CI) – v buildu `play` odebráno | – |

## Data safety (návrh odpovědí)
- **Sbíraná data:** žádná vývojáři – VEGA nemá vlastní server ani analytiku.
- **Sdílená data:** text dotazu a uživatelem schválené podklady jdou zvolenému poskytovateli modelu (Google Gemini, Groq…,
  nebo vlastní server) – jen po souhlasu, přenos šifrovaně (HTTPS; http jen do lokální sítě k vlastnímu serveru).
- **Poloha:** nesbírá se (oprávnění jen kvůli API seznamu WiFi).
- **Uložení:** vše v telefonu; klíče v trezoru (Android Keystore); záloha systému vypnutá (`allowBackup=false`).
- **Smazání:** smazání konverzací/paměti v aplikaci; odinstalace smaže vše.

## Další poznámky k zásadám
- Rozšíření a testy projektu (JS) běží v Workeru WebView – výjimka pro interpretovaný kód ve WebView; nestahuje se nativní kód.
- Root (libsu) je volitelný a vypnutý; aplikace funguje bez něj.
- Funkce Hardware: žádné klonování přístupových/platebních karet, žádné rušení WiFi (deauth) – v souladu se zásadami.

## Jak vydat
1. CI (`build-vega.yml`) → artefakt `VEGA-PLAY-AAB` → nahrát do Play Console (interní testování).
2. Vyplnit Data safety a deklaraci oprávnění podle tabulek výše.
3. Ověřit na Pixelu 9a (Android 16): edge-to-edge, gesto Zpět (predictive back – VEGA používá `OnBackPressedDispatcher`), WiFi/BLE/NFC.
