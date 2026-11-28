# Placený pilot na jedné 24GB GPU (spouští vlastník projektu)

**Rozpočet: 500 Kč celkem** (instance + úložiště + rezerva na daň). Práce anotátora je zvláštní položka.

Kontext pro odhad:

- Pilot s dnešními daty (115 trénovacích dvojic) proběhl na CPU kontejneru za **13,5 s / 6 epoch**. GPU dává smysl až s většími daty, kolem 600+ lidsky zkontrolovaných dvojic, nebo s větším encoderem.
- Orientační cena instance s RTX 4090 / A5000 / L4 (24 GB) u běžných pronajímatelů je kolem 0,3–0,8 USD/h. Ověř ji v den běhu a zapiš skutečnou.

## 1. Příprava (bez GPU, zdarma)
1. Doplň a zkontroluj data podle ANOTACE.md, pak `python build_data.py && python validate_data.py`.
2. Rozhodni, kolik běhů se vejde do rozpočtu. Výchozí návrh je nejvýš 6 běhů po ≤ 20 min.

## 2. Na instanci
```
git clone <repozitář> && cd Moje-axiom-omega/tools/verifier
pip install torch==2.5.1 --index-url https://download.pytorch.org/whl/cu121
pip install -r requirements.txt
date -u +%FT%TZ > runs_start.txt                      # začátek účtovaného času
nvidia-smi --query-gpu=name,memory.total --format=csv | tee gpu.txt
python smoke_cpu.py                                   # technický běh (na GPU taky projde)
python evaluate.py --out runs/ref-val.json            # výchozí srovnání PŘED tréninkem
```

## 3. Prvních 15 minut: změřit propustnost a paměť
```
python train.py --out runs/probe --epochs 1 --bs 32 --train-layers 6 --max-steps 200
python -c "import torch;print(torch.cuda.max_memory_allocated()/2**30,'GiB')"
```
Ze záznamu `runs/probe/train_log.json` (sekundy na krok) přepočítej:

- čas na epochu = počet řádků / velikost dávky × sekundy na krok;
- počet běhů, které se vejdou do zbytku rozpočtu (cena za hodinu × hodiny ≤ zbytek rozpočtu − 50 Kč rezerva).

Když nevyjde aspoň 1 celý běh se zálohou, **skonči**.

## 4. Běhy
```
python train.py --out runs/r1 --epochs 6 --bs 32 --train-layers 2
python train.py --out runs/r2 --epochs 6 --bs 32 --train-layers 6 --lr 2e-5
python evaluate.py --model runs/r1/ckpt-6 --out runs/r1/val.json        # výběr JEN na validaci
python evaluate.py --model <vítěz> --final --out runs/final.json        # JEDNOU
python export_onnx.py --model <vítěz> --name kandidat --threshold <práh z validace> --out bundles/kandidat
```
Přerušený běh obnovíš přes `--resume`. Kontrolní bod se ukládá po každé epoše.

## 5. Ukončení (nic nenechat běžet)
```
date -u +%FT%TZ > runs_end.txt
tar czf vysledky.tgz runs/*/train_log.json runs/*/*.json bundles/kandidat gpu.txt runs_start.txt runs_end.txt
```
1. Stáhni `vysledky.tgz` a ověř kopii: `sha256sum` na obou stranách, `tar tzf`.
2. **Ukonči instanci** (terminate, ne jen stop).
3. **Smaž placené úložiště / svazek a snapshoty.**
4. Do PROTOKOL.md zapiš skutečnou cenu za hodinu, běžící čas (end − start) a celkovou cenu.
