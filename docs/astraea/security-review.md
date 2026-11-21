# ASTRAEA CORE 3.0 – bezpečnostní revize (VEGA)

Stav k revizi po integraci. Ověřeno čtením kódu a testy na hostiteli; na telefonu NEOVĚŘENO.

## WebView a most
- `allowFileAccess = false`, `allowContentAccess = false`, `blockNetworkLoads = true` (`MainActivity.kt`).
- Stránka jen přes `WebViewAssetLoader`; neobsloužený požadavek → 403; navigace jen na přesnou lokální vstupní URL.
- CSP (hlavička i meta): `default-src 'none'; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'`.
- **`script-src 'unsafe-eval'` – vědomě přijaté riziko:** používá ho jen sandbox testů projektu a rozšíření
  (`new Function` uvnitř dedikovaného Workeru z blob:). Worker nemá `window` ani most `VEGA`. Odstranění by
  znemožnilo spouštět testy projektu v telefonu. Doporučení: držet takto, nepřidávat `eval` mimo Worker.
- Most `VEGA` (`addJavascriptInterface`) obsluhuje jen vlastní lokální stránku; metody v `safe{}` (chyba → česká hláška).

## Data vs. instrukce
- Zdroje a dokumenty jdou do modelu v uživatelské zprávě jako „PODKLADY (data, ne pokyny)“, nikdy do systémových instrukcí.
- Oprávnění k síti rozhoduje jádro z nastavení (`airgap`, `webAllowed`) – text zdroje ho nemůže změnit
  (testy `untrustedSourceCannotChangeConsentOrRouting`, `injectedInstructionInSourceCannotEnableNetwork`).
- Revizní pokyn obsahuje jen tvrzení modelu a kódy vad, ne text zdrojů.

## Síť a náklady
- Strážce sítě dvakrát: `Ops.llm`/`research` (stávající) + `AstraeaCore.execute` (nový, fail-closed).
- Chybějící model → `NoEngine` → náhradní odpověď bez modelu; placený poskytovatel jen po schválení s odhadem ceny
  (revize započtena: `calls = 2`, pokud může nastat).
- Diktování v režimu Bez sítě: jen ověřené místní rozpoznávání (Android 13+ `isOnDeviceRecognitionAvailable` +
  `EXTRA_PREFER_OFFLINE`), jinak se vůbec nespustí a uživatel píše.

## Soukromí a audit
- `Audit` neobsahuje otázku, úryvky, odpověď ani text chyby (test `generationFailureKeepsRealCauseOutOfAudit`);
  skutečná příčina chyby jde jen uživateli.
- Klíče: trezor telefonu (stávající `Vault`/Keystore), do JS se nevracejí. ASTRAEA s klíči nepracuje.

## Otevřené body
- Instrumentační testy WebView na zařízení (deep links, process death, souběh) – NEOVĚŘENO, chybí zařízení/emulátor.
