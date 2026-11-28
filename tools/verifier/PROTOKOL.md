# Protokol – ověřovač opory ve zdroji

Výchozí revize VEGA: **be714d6**.

Všechna měření proběhla na **hostiteli** (Linux kontejner se 4 jádry CPU). Nic se neměřilo na Pixelu 9a ani v emulátoru. Štítky dat jsou **návrhy modelu bez lidské kontroly**, takže všechna čísla kvality jsou **předběžná**.

## 1. Napojení na aplikaci a výchozí chování

### Cesta v kódu

1. `Ops.research()` / `Ops.hypothesis()` stáhne pasáže (`WebGround.gather`).
2. Pasáže jdou modelu jako `[n]`.
3. Odpověď s citacemi `[n]` se zobrazí s kartou `sources`. U každého zdroje je titul, URL, datum stažení a původní text.

### Výchozí revize be714d6

- Citace se zobrazí **bez jakéhokoli ověření**.
- Záměnu osoby („Jan“ vs. „Petr Novák“) ani větu, která jen oznamuje plán („bylo naplánováno“ vs. „sešel se“), VEGA nijak neoznačí.
- Ze 22 nepodložených dvojic závěrečné sady by tedy uživatel u **22/22** neviděl žádné upozornění.

### Změna

Nové chování popisuje `Ops.groundChecks()`:

- Odpověď se rozdělí na atomická tvrzení (`Grounding.claims`). Souvětí s příčinou se dělí na událost/datum a motiv.
- Každé tvrzení × každá jeho citace × **původní** pasáž dostane vlastní výsledek. Rozpory mezi zdroji se tak nesčítají.
- Karta ukazuje štítek u citace. Klepnutím se otevře pasáž v originále se zvýrazněným oknem, které model četl.
- Bez balíčku vah, s poškozeným balíčkem, při chybě nebo při ztrátě rozhodujícího kontextu zkrácením se zobrazí **NEVYHODNOCENO** s důvodem. Chat přitom funguje beze změny.

## 2. Výchozí srovnání: referenční NLI bez dotrénování

Na příkladech ze zadání (ONNX na hostiteli i v Kotlinu přes ONNX Runtime pro JVM):

| Pasáž → tvrzení | Očekávání | Referenční | Kandidát (pilot) |
|---|---|---|---|
| „scheduled to meet on Monday“ → „Na pondělí bylo naplánováno jednání“ | PODPORUJE | PODPORUJE | PODPORUJE |
| „scheduled to meet on Monday“ → „Výbor se v pondělí sešel“ | NEDOSTAČUJE | **PODPORUJE (0,98)** | **PODPORUJE (0,84)** |
| „did not meet on Monday“ → „Výbor se v pondělí sešel“ | ODPORUJE | ODPORUJE | – |
| „Petr Novák zvolen“ → „Jan Novák zvolen“ | ne PODPORUJE | ODPORUJE | ODPORUJE |
| „plánuje otevřít v roce 2027“ → „otevřelo“ | NEDOSTAČUJE | **PODPORUJE (0,99)** | **PODPORUJE (0,92)** |

Plán vs. uskutečnění zůstává hlavní slabinou obou modelů. Dotrénování na pilotních datech ji nevyřešilo.

## 3. Pilotní dotrénování (CPU, 115 dvojic)

| Položka | Hodnota |
|---|---|
| Trénovatelné části | Třícestná hlava a 2 horní vrstvy encoderu (3 697 923 parametrů) |
| Zmrazené části | Embeddingy a spodní 4 vrstvy |
| Hyperparametry | lr 3e-5, dávka 8, 8 epoch, seed 20261119 |
| Čas | 6 epoch za 13,5 s, obnova z bodu epochy 6 → 8 za 4,5 s |
| Ztráta | 1,00 → 0,36 |
| Zkouška `smoke_cpu.py` | 3 kroky; změna vah 3,0e-3; uložit; znovu načíst (váhy shodné); vyhodnocena 1 dvojice |

Práh podpory se vybíral **na validaci**: nejnižší práh s precision PODPORUJE ≥ 0,9.

- referenční model: práh 0,91;
- kandidát: práh 0,62.

### Uzamčená závěrečná sada (36 dvojic, 6 dokumentů, otevřena jednou)

| Kombinace | Model | Falešné podpory | Precision PODPORUJE | Recall PODPORUJE | NEVYHODNOCENO |
|---|---|---|---|---|---|
| cs pasáž → cs tvrzení | referenční | 3/12 | 6/9 | 6/7 | 0/19 |
| cs pasáž → cs tvrzení | kandidát | 3/12 | 6/9 | 6/7 | 0/19 |
| en pasáž → cs tvrzení | referenční | 0/10 | 3/3 | 3/7 | 0/17 |
| en pasáž → cs tvrzení | kandidát | 1/10 | 4/5 | 4/7 | 0/17 |
| *bez ověřovače (be714d6)* | – | *22/22 bez upozornění* | – | – | – |

Matice záměn jsou v `docs/vega/mereni/overovac-eval-final.json` (řádek = pravda, sloupec = předpověď).

**Rozdíl kandidát − referenční** (95% interval, bootstrap po dokumentech, 2 000 vzorků):

| Kombinace | Falešné podpory | Recall |
|---|---|---|
| cs→cs | [0,00; 0,00] | [0,00; 0,00] |
| en→cs | [0,00; +0,25] | [0,00; +0,33] |

### Rozhodnutí podle předem stanoveného pravidla

Pravidlo zní: kandidát se smí nasadit, jen když v **každé** kombinaci klesnou falešné podpory a recall se zhorší nejvýš o 5 p. b.

- **Nesplněno:** v en→cs falešné podpory vzrostly (0/10 → 1/10), v cs→cs se nezměnily.
- Rozdíly jsou malé a neprůkazné, proto výsledek **označuji k dalšímu ověření**.
- **Kandidát se nenasazuje.**

## 4. Export a kvantizace

Shoda rozhodnutí se měřila na stejných 59 dvojicích (validace + závěrečné vstupy) proti PyTorch modelu.

| Varianta | Velikost | Shoda rozhodnutí | Max. rozdíl pravděpodobnosti | Stav |
|---|---|---|---|---|
| ONNX fp32 | 428 MB | 59/59 | 3e-6 | jen meziprodukt |
| ONNX dynamická kvantizace int8 (vše) | 107 MB | **22/59** | 0,96 | **zamítnuto** |
| int8 jen váhy (embeddingy + MatMul) | 109 MB | 57/59 | 0,064 | zamítnuto (2 rozhodnutí jinak) |
| **int8 jen tabulka embeddingů**, zbytek fp32 | **141 MB** | **59/59** | 0,015 | **použito** |

Tokenizer v Kotlinu (`UnigramTokenizer`) vrací stejná id tokenů jako originál u **40/40** testovacích vektorů.

## 5. Výkon (hostitel, ne telefon)

| Měření | Hodnota |
|---|---|
| Načtení balíčku v Kotlinu (manifest + otisky + slovník + ONNX session) | 873 ms |
| Jedna dvojice, krátká, Kotlin + ONNX Runtime JVM | p50 8 ms, p95 9 ms |
| Jedna dvojice se 124 tokeny, Python ONNX Runtime | p50 16,7 ms, p95 22,8 ms |
| Vrchol RSS po načtení modelu (Python ORT) | 280 MB |
| Velikost balíčku | 141 MB model + 7,8 MB slovník |
| Přírůstek APK o knihovnu ONNX Runtime | změří CI build |

### Pixel 9a – NEOVĚŘENO

Fyzický telefon v tomto prostředí není. Postup pro vlastníka:

1. Nainstaluj APK.
2. Přepni telefon do režimu letadlo.
3. Nastavení → Ověřovač citací → Nainstalovat balíček (`referencni.zip`).
4. V chatu napiš hypotézu nebo rešerši s uloženými zdroji, nebo otevři starší odpověď se zdroji, a ověř štítky u citací.
5. Klepni na štítek a ověř, že se otevře přesná pasáž.
6. Restartuj aplikaci a ověř, že se balíček načte znovu.
7. Poškoď balíček (nahraj zkrácený `model.onnx`) a ověř stav „NEVYHODNOCENO – soubor je poškozený“.
8. Změř velikost, vrchol RAM (`adb shell dumpsys meminfo cz.axiom.vega`), první spuštění a medián i 95. percentil času na dvojici.

## 6. Co VEGA načítá teď a jak se nahradí

- **Ve výchozím stavu se nenačítají žádné váhy.** APK je neobsahuje a citace se zobrazí jako „nevyhodnoceno – ověřovač není nainstalovaný“.
- Po instalaci `referencni.zip` načte VEGA **referenční NLI** `MoritzLaurer/multilingual-MiniLMv2-L6-mnli-xnli@0a71e92` s prahem 0,91.
  - Proti výchozí revizi bez ověření snižuje počet nepodložených dvojic bez upozornění (předběžně: 22/22 → 3/22).
  - Má ale doloženou slabinu „plán vs. uskutečnění“.
  - Do výchozího nastavení se proto **nenasazuje automaticky**. Instalaci provádí uživatel.
- Dotrénovaný balíček se nahradí stejně:
  1. `export_onnx.py --model <vítěz>`;
  2. zip;
  3. Nastavení → Nainstalovat balíček.
- Doporučení: nasadit dotrénovaný balíček až po lidské kontrole závěrečné sady a splnění pravidla z bodu 3.

## 7. Náklady

Placený běh **neproběhl**, utraceno 0 Kč. Všechno výše běželo na CPU kontejneru. Plán je v GPU_PLAN.md.
