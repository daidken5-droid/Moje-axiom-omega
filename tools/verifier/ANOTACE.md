# Pokyny pro anotátora

Formulář je v souboru `data/annotation_queue.csv`. Je v UTF-8 se středníky a otevře se v Excelu i LibreOffice.

U každého řádku čti **jen pasáž** v originále, ne celý článek ani svoje znalosti. Do sloupce `rozhodnuti` vyber jednu možnost:

| Rozhodnutí | Kdy |
|---|---|
| **PODPORUJE** | Pasáž tvrzení přímo dokládá. Stačí parafráze nebo překlad. |
| **ODPORUJE** | Pasáž tvrzení vylučuje: jiné datum, číslo nebo osoba v jednoznačné roli, zápor. |
| **NEDOSTAČUJE** | Údaj v pasáži není. Patří sem i plán vs. uskutečnění („bylo naplánováno“ ≠ „sešel se“), „obvykle“ vs. „vždy“ a věcně blízká pasáž o něčem jiném. |
| **VYŘADIT** | Příklad je nejednoznačný nebo chybný. Napiš proč do `poznamka`. |

U závěrečné sady je sloupec `navrh_stitku` prázdný, aby tě nic neovlivnilo. Klíč závěrečné sady nikomu z trénovacího postupu neukazuj.

## Návrat vyplněného formuláře

Vyplněný formulář vrať vývojáři. Ten rozhodnutí přenese:

- do `seed_claims.py` (štítek), nebo
- do stavu `zkontrolovano` / `vyrazeno` v datech.

Potom spustí `build_data.py` a `validate_data.py`. Měření se přestanou označovat jako předběžná, až bude zkontrolovaná celá závěrečná sada.
