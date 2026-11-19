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
- [ ] 1. Nové jádro (`cz.axiom.vega.base/net/llm/web/calc/files`): hloubková kontrola 3 úhly (bezpečnost, správnost, mrtvý kód) → opravy + testy
- [ ] 2. Úklid úložiště podle stáří a velikosti: `resume/`, `taskfile/`, `cache/long`, `evidence/`, staré `run/` (velké přílohy uložit jednou podle otisku)
- [ ] 3. Web: proč DuckDuckGo z kontejneru nic nevrací; záložní vyhledávání (Wikipedie search už je); test parseru na uloženém HTML
- [ ] 4. Kontrola všech regexů na Android (ICU): u každého `Regex("…")` ve VEGA ověřit konstrukce; rozšířit AndroidRegexTest o `\b` za ne-ASCII
- [ ] 5. Autonomie krok 1: `SelfTune` deník signálů slabin (chyby, opravy uživatele, Stop, špatný záměr, pomalé kroky) + test
- [ ] 6. Autonomie krok 2: návrh příkladů routeru z deníku → ověření na held-out sadě → karta „Vylepšení“ s historií a návratem
- [ ] 7. Autonomie krok 3: rozšíření jako JS nástroje v sandboxu (registr, oprávnění, testy, zapnout/vrátit)
- [ ] 8. UI: dotaz na souhlas vs. jiné listy (už hlídáno), spinner NFC po idle, přístupnost (aria, kontrast), kontrola na 360 px šířky
- [ ] 9. Výkon: profil `Ops.home()` a vykreslování chatu se 300 zprávami; měření před/po
- [ ] 10. Finální: CI APK, SHA-256, shrnutí noci v `docs/vega/README.md`

## Problémy
(zatím nic)
