# Noční plán – deep scan a vylepšení VEGA

Pravidla pro každé probuzení:

1. Vezmi první nezaškrtnutý bod.
2. Udělej ho celý: kód, test, `./gradlew :vegacore:test :vega:testDebugUnitTest`.
3. Commitni a pushni na větev `claude/reverse-engineer-ai-app-o5jhyd`.
4. Bod zaškrtni a připiš krátký výsledek a commit.

Další pravidla:

- AVIOR (`app/`, `core/`) neměnit.
- Žádné placené volání, nic nefalšovat.
- Neprošlo-li něco: zapsat do „Problémy“ a pokračovat dalším bodem.
- **Kontrola Android regexů:** žádné `(?U)` ani `\b` za českým písmenem. Hlídá to AndroidRegexTest.

## Body
- [x] 1. Nové jádro (`cz.axiom.vega.base/net/llm/web/calc/files`): hloubková kontrola 3 úhly (bezpečnost, správnost, mrtvý kód) → opravy + testy
  - **Výsledek:** CoreScanTest (3 testy), celkem 39 + 21 testů zelených.
  - **Bezpečnost:**
    - SSRF přes jméno (DNS na 127.0.0.1 / LAN) – veřejný web teď překládá jméno a kontroluje všechny adresy;
    - IPv4 v IPv6 (`::ffff:127.0.0.1`), `localhost.`, `*.localhost`, multicast;
    - vlastní hlavičky se neposílají na cíl přesměrování jinam.
  - **Rozpočet:** `check` + `reserve` byly dvě operace, takže dvě souběžná placená volání mohla přečerpat strop. Nově je to atomické `tryReserve`.
  - **Správnost:**
    - „dnes je hezky“ už nevrací datum;
    - unce po odstranění diakritiky;
    - „dnes“ v počtu dní bere čas z parametru.
  - **Mrtvý kód:** nic, všechny veřejné funkce jádra se používají.
  - **Zbývá:** DNS rebinding (překlad ověřený zvlášť před spojením) jde plně řešit jen vlastním resolverem. Je to zapsané v Problémech.
- [x] 2. Úklid úložiště podle stáří a velikosti: `resume/`, `taskfile/`, `cache/long`, `evidence/`, staré `run/` (velké přílohy uložit jednou podle otisku)
  - **Výsledek:** `Housekeeping` běží při startu na výkonném vlákně. HousekeepingTest (2 testy) prošel.
  - **Nalezený únik:** seznam chatů se ořezával na 50, ale zprávy, podklady, návrhy, rozpracované dotazy a porovnání vypadlých chatů zůstávaly navždy. Teď se mažou.
  - **Mazání podle stáří:**
    - dočasný chat po 1 dni;
    - „Pokračovat“ po 7 dnech;
    - mezivýsledky dlouhých zadání po 3 dnech;
    - úlohy bez odkazu po 30 dnech;
    - osiřelé přílohy úloh (ukládají se jednou podle otisku).
  - **Bezpečnost úklidu:** chybějící nebo rozbitý seznam chatů = nic se nemaže.
  - `Kv.modified()` je nová funkce. Verze 1.4.0 (versionCode 6).
- [x] 3. Web: proč DuckDuckGo z kontejneru nic nevrací; záložní vyhledávání (Wikipedie search už je); test parseru na uloženém HTML
  - **Příčina:** parser je v pořádku, na skutečné stránce našel 10/10 výsledků. Selhávalo spojení: tunel proxy v kontejneru padal uprostřed přenosu. DDG navíc botům občas vrací stránku „anomaly“ (HTTP 202).
  - **Oprava:**
    - jeden opakovaný pokus;
    - stránka „anomaly“ = prázdný výsledek bez chyby;
    - `duckParse` je oddělený kvůli testu.
  - **Test:** WebParseTest na uloženém vzorku skutečného HTML (3 výsledky, dekódování uddg, odstranění `<b>`).
  - **NEOVĚŘENO:** lite.duckduckgo.com z kontejneru neodpověděl, takže parser pro něj jsem nenapsal naslepo.
- [x] 4. Kontrola všech regexů na Android (ICU): u každého `Regex("…")` ve VEGA ověřit konstrukce; rozšířit AndroidRegexTest o `\b` za ne-ASCII
  - **Výsledek:**
    - `Ops.kt` „najdi v mých souborech/souborů X“: `souborů)\b` se na telefonu a v testu chovalo různě. Opraveno na `(?!\p{L})`.
    - AndroidRegexTest hlídá `\b` vedle českého písmene, i přes skupiny `(…|souborů)\b`. Sám detektor má vlastní test.
    - Ostatní nálezy jsou `\b` jen u ASCII písmen, tedy v pořádku. CANON_EQ, COMMENTS ani neomezený lookbehind se nepoužívají.
    - Testy: 44 + 21 zelených.
- [x] 5. Autonomie krok 1: `SelfTune` deník signálů slabin (chyby, opravy uživatele, Stop, špatný záměr, pomalé kroky) + test
  - **Výsledek:** `core/SelfTune.kt`. SelfTuneTest (2 testy) prošel.
  - **Signály se zapisují do deníku po dnech:**
    - chyba (výpadky modelu zvlášť jako oblast „model“);
    - Stop během práce;
    - krok delší než 45 s;
    - oprava od uživatele („ne, myslel jsem…“);
    - přeformulování téhož dotazu do 90 s.
  - **Připisování:** signál se připíše oblasti, která předchozí zprávu zpracovala (záměr routeru / nástroj / postup).
  - **Soukromí:** klíče, e-maily, URL (zůstane jen doména), IBAN a delší čísla se nahrazují značkou. Věta má max. 160 znaků, bez obsahu souborů. Deník drží 30 dní a nejvýš 400 záznamů denně.
  - **Bezpečnost:** zápis nikdy neshodí práci.
- [x] 6. Autonomie krok 2: návrh příkladů routeru z deníku → ověření na held-out sadě → karta „Vylepšení“ s historií a návratem
  - **Výsledek:** `RouterTuning` + `Router.Learned` + karta Menu → Vylepšení. RouterTuningTest (2 testy) prošel.
  - **Odkud se pravidlo bere:** z deníku, když zpráva skončila v obecném rozhovoru / rešerši a uživatel ji hned opravil nebo přeformuloval. Navazující zpráva ukáže správnou akci.
  - **Kdy se zapne:**
    - samo po 2 dokladech;
    - jen když nezmění žádný z 41 případů kontrolní sady a své příklady pošle správně;
    - jinak zůstane jako návrh na klepnutí.
  - **Bezpečnost:**
    - pravidlo smí jen nahradit obecný rozhovor / rešerši, pevná pravidla nikdy nepřebije;
    - vrácené pravidlo se samo znovu nezapne;
    - každá změna má historii.
  - **Karta:**
    - naučená pravidla (stav, výrazy, doklady, historie, Vrátit / Zapnout / Zamítnout);
    - souhrn slabin za 30 dní;
    - po samozapnutí přijde oznámení.
  - **NEOVĚŘENO:** vzhled karty na telefonu (zkontroluje se v bodě 8).
- [x] 7. Autonomie krok 3: rozšíření jako JS nástroje v sandboxu (registr, oprávnění, testy, zapnout/vrátit)
  - **Výsledek:** `core/Extensions.kt` + `runExtension` ve Workeru + karta Vylepšení → Rozšíření.
  - **Postup:**
    1. Uživatel napíše „napiš rozšíření, které…“.
    2. VEGA založí projekt s pevným tvarem: `tool.js` bez require + `tool.test.js`.
    3. Na „ulož jako rozšíření“ proběhnou testy ve Workeru nad PŘESNĚ těmito soubory.
    4. Nová verze se uloží jen s otiskem, který testy prošel.
    5. Rozšíření se objeví v nástrojích pod „Rozšíření“.
  - **Správa verzí:** nové rozšíření je vypnuté. Zapnout jde jen otestovaný otisk, předchozích 5 verzí jde vrátit, vše má historii.
  - **Sandbox (ověřeno v Chromiu na hostiteli, NE v telefonu):**
    - `fetch`, `XHR` a `importScripts` jsou nedostupné;
    - `require` zablokovaný;
    - nekonečná smyčka skončí na limitu;
    - podvržený výsledek z kódu rozšíření se ignoruje (nonce).
  - **Testy:** ExtensionsTest (2 testy) prošel.
- [x] 8. UI: dotaz na souhlas vs. jiné listy (už hlídáno), spinner NFC po idle, přístupnost (aria, kontrast), kontrola na 360 px šířky
  - **Výsledek (snímky z Chromia na hostiteli, 360 px, světlý i tmavý režim, bez chyb JS a bez přetečení):**
    - Karta Vylepšení: s 2–3 tlačítky se text smrskl na jedno slovo na řádek. Tlačítka jsou teď pod textem (`.row.stack`).
    - Technické štítky (`záměr:Talk`, `ChainException`) se zobrazují česky.
    - Spinner NFC zmizel hned po idle, i když telefon poslouchal. Teď se řídí skutečným stavem poslechu (`nfc.listening` → zpráva).
    - Kontrast: bílý text na modré #5b8cff měl 3,16:1. Nově `--signal-strong` #3567e8 (4,93:1, WCAG AA) pro tlačítka a odeslání. Šedý text na listu má 5,7:1.
- [x] 9. Výkon: profil `Ops.home()` a vykreslování chatu se 300 zprávami; měření před/po
  - **Výsledek (Chromium na hostiteli, 300 zpráv):**
    - Plné vykreslení chatu: 50–70 ms → 14–15 ms, uzly 5 558 → 1 489. Vykresluje se posledních 80 zpráv, starší jsou na tlačítku „Zobrazit starší“.
    - Stream 600 průběžných částí: 0,99 s → 0,83 s celkem (už předtím rychlá cesta přes `#live`).
  - **NEOVĚŘENO:** `Ops.home()` na telefonu. Bez zařízení nejde změřit pravdivě, odhad z hostitele by byl zavádějící.
- [ ] 10. Finální: CI APK, SHA-256, shrnutí noci v `docs/vega/README.md`

## Problémy
- DNS rebinding: Http ověří adresy jména, ale HttpURLConnection jméno překládá znovu (TOCTOU). Plné řešení = připojení na ověřenou IP s SNI; zatím nízké riziko (útočník musí ovládat DNS a VEGA musí stáhnout jeho stránku).
