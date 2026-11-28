# Ověřovač opory ve zdroji – trénovací balík

Model ověřovače dostane dvojici: české atomické tvrzení a původní pasáž (česky nebo anglicky). Vrátí jednu ze tří tříd:

- **PODPORUJE** – pasáž tvrzení dokládá;
- **ODPORUJE** – pasáž tvrzení vyvrací;
- **NEDOSTAČUJE** – pasáž tvrzení nedokládá ani nevyvrací.

Výsledek se týká jen obsahu této jedné pasáže. Neříká nic o tom, jestli je tvrzení pravdivé.

VEGA ověřovač spouští v telefonu přes ONNX Runtime a funguje i v režimu letadlo. Integraci v aplikaci popisuje [PROTOKOL.md](PROTOKOL.md).

## Model

- **Základ i výchozí srovnání:** [`MoritzLaurer/multilingual-MiniLMv2-L6-mnli-xnli`](https://huggingface.co/MoritzLaurer/multilingual-MiniLMv2-L6-mnli-xnli) v revizi `0a71e92a985b6e1ad1828cf67ce9c459639c1dca`, licence MIT.
  - Je to cross-encoder: pasáž a tvrzení čte společně.
  - Má 6 vrstev, skrytou dimenzi 384 a slovník XLM-R (250 002 kusů).
- **Pořadí tříd** odpovídá výstupům modelu: `PODPORUJE`, `NEDOSTAČUJE`, `ODPORUJE` (v modelu `entailment`, `neutral`, `contradiction`).
- **Velikost balíčku:** `model.onnx` 141 MB plus `vocab.tsv` 7,8 MB.
  - Tabulka embeddingů je kvantovaná do int8 po řádcích, ostatní váhy zůstávají fp32.
  - Plná dynamická kvantizace int8 (107 MB) shodu rozbila: rozhodnutí sedělo jen u 22 z 59 dvojic, viz protokol.

## Příkazy

Spouštějí se z adresáře `tools/verifier`. Kromě `bundles/` a `runs/` (necommitované) mají všechny skripty pevný seed 20261119.

```
pip install -r requirements.txt                 # verze připnuté
python build_data.py                            # dataset; rozdělení podle dokumentů, klíč závěrečné sady do locked/
python validate_data.py                         # schéma, licence, únik mezi částmi, vyváženost, skutečné počty
python smoke_cpu.py                             # zkouška na CPU: náhodná hlava, 3 kroky učení, uložit, načíst, 1 dvojice
python evaluate.py --out runs/ref-val.json      # výchozí model (referenční NLI) na validaci; práh se vybírá zde
python train.py --out runs/pilot --epochs 6     # dotrénování (hlava + 2 horní vrstvy), kontrolní bod po každé epoše
python train.py --out runs/pilot --epochs 8 --resume   # obnova z posledního kontrolního bodu
python evaluate.py --model runs/pilot/ckpt-8 --final --out runs/pilot/eval-final.json   # JEDNOU, uzamčený klíč
python export_onnx.py --name referencni --threshold 0.91 --out bundles/referencni
python export_onnx.py --model runs/pilot/ckpt-8 --name kandidat-pilot --threshold 0.62 --out bundles/kandidat-pilot
(cd bundles/referencni && zip ../referencni.zip manifest.json model.onnx vocab.tsv)   # balíček pro VEGA (Nastavení → Ověřovač citací)
```

## Data

| Soubor | Obsah |
|---|---|
| `data/documents.json` | Úvodní pasáže 21 článků Wikipedie: 11 českých, 10 anglických; dějiny, technika, veřejné instituce. U každé je přesná revize a licence CC BY-SA 4.0. |
| `seed_claims.py` | 112 ručních NÁVRHŮ dvojic, které napsal jazykový model. Nejsou lidsky posouzené. |
| `data/pairs.jsonl` | Trénovací a validační řádky. Každý má identitu zdroje, revizi, pozici v textu, licenci, jazyk, štítek, jevy, rodinu, původ anotace, stav a odůvodnění. Syntetické řádky mají `synthetic: true`. |
| `data/final_inputs.jsonl` | Závěrečné vstupy bez štítků. |
| `locked/final_key.csv` | Klíč závěrečné sady. Čte ho jen `evaluate.py --final`. |
| `data/annotation_queue.csv` | Formulář pro anotátora, viz [ANOTACE.md](ANOTACE.md). |
| `data/manifest.json` | Skutečné počty, otisky souborů a dokumenty po částech. |

### Plán a skutečnost

| Část | Plán pilotu | Skutečně | Z toho |
|---|---|---|---|
| Trénink | 600 | 115 | 53 ručních návrhů, 62 syntetických |
| Validace | 120 | 23 | vše ruční návrhy |
| Závěrečná sada | 180 | 36 | vše ruční návrhy |

Lidsky zkontrolovaných řádků je zatím 0, takže všechna měření jsou **předběžná**.

Postup sestavení dat:

1. Dataset se rozdělí podle dokumentů. Tím zůstanou rodiny parafrází, překlady téže věci i související události v jedné části.
2. Teprve potom vzniknou syntetické varianty (zápor, změna čísla nebo data, „plánovalo se“, pasáž z cizího dokumentu), a to jen z trénovacích dokumentů.

## Placený trénink

Postup je v [GPU_PLAN.md](GPU_PLAN.md). Placený běh spouští vlastník projektu.
