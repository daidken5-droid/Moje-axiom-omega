# SECURITY_REVIEW – hranice JavaScript ↔ Android a zálohy (1014)

## Threat model
Útočník = cizí JavaScript ve WebView (XSS přes vykreslený obsah, podvržená stránka) nebo osoba s přístupem k datasetu zálohy.
Obrana 1: obsah jen z AssetLoaderu, CSP, escapování, `open()` jen https (stávající, IMPLEMENTED). Obrana 2 (nová): i když
JS běží, nesmí sám provést citlivou akci.

| Oblast | Stav | Důkaz |
|---|---|---|
| Tajné klíče do JS | most je nevrací (`saveKey` jen zápis, `vault.read` jen v Kotlinu) | čtení `OmegaBridge.kt` – TESTED čtením kódu |
| Root povel | token z Kotlinu + **nativní dialog** s plným zněním příkazu | IMPLEMENTED; na zařízení neověřeno |
| Smazat vše / vymazat paměť / obnova z cloudu / root mazání cache | nativní dialog | IMPLEMENTED; na zařízení neověřeno |
| Cloudová záloha | E2EE AES-256-GCM, PBKDF2 310k, AAD, verze formátu, heslo neukládáno | `BackupCryptoTest` TESTED |
| Stará nešifrovaná záloha | nepřevádí se ani nemaže potichu; obnova s upozorněním | IMPLEMENTED |
| Poškozená záloha | obnova odmítne neplatnou SQLite | IMPLEMENTED |
| Oprava od uživatele jako instrukce | uvozený text v paměti „data, ne instrukce“, typ CORRECTION | `CorrectionIntegrityTest` TESTED |

## Otevřené (UNKNOWN / DESIGNED)
- Most má stále 102 metod; rozdělení oprávnění podle obrazovky a validace všech parametrů: DESIGNED, ne IMPLEMENTED.
- XSS test v reálném WebView: BLOCKED (emulátor C-08, chybí telefon). Žádný konkrétní exploit nebyl nalezen ani tvrzen.
- Široká oprávnění (`REQUEST_INSTALL_PACKAGES`, `WRITE_SETTINGS`, `SET_ALARM`) beze změny.
- JNI / GGUF fuzz: nedělán.
