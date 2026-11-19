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
- [ ] 4. Kontrola všech regexů na Android (ICU): u každého `Regex("…")` ve VEGA ověřit konstrukce; rozšířit AndroidRegexTest o `\b` za ne-ASCII
- [ ] 5. Autonomie krok 1: `SelfTune` deník signálů slabin (chyby, opravy uživatele, Stop, špatný záměr, pomalé kroky) + test
- [ ] 6. Autonomie krok 2: návrh příkladů routeru z deníku → ověření na held-out sadě → karta „Vylepšení“ s historií a návratem
- [ ] 7. Autonomie krok 3: rozšíření jako JS nástroje v sandboxu (registr, oprávnění, testy, zapnout/vrátit)
- [ ] 8. UI: dotaz na souhlas vs. jiné listy (už hlídáno), spinner NFC po idle, přístupnost (aria, kontrast), kontrola na 360 px šířky
- [ ] 9. Výkon: profil `Ops.home()` a vykreslování chatu se 300 zprávami; měření před/po
- [ ] 10. Finální: CI APK, SHA-256, shrnutí noci v `docs/vega/README.md`

## Problémy
- DNS rebinding: Http ověří adresy jména, ale HttpURLConnection jméno překládá znovu (TOCTOU). Plné řešení = připojení na ověřenou IP s SNI; zatím nízké riziko (útočník musí ovládat DNS a VEGA musí stáhnout jeho stránku).
