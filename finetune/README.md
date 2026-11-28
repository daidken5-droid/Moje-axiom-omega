# Doladění AVIORu (fine-tuning)

AVIOR se dolaďuje ve **třech vrstvách**:

| Vrstva | Kde běží | Co dělá |
|---|---|---|
| **1. Učení z každé otázky** | v telefonu, automaticky | mapa kompetencí, kalibrace jistoty, vlastní poznatky, samostudium (`core/self/Cognition.kt`) |
| **2. Doladění dovedností ukázkami** | v telefonu, automaticky | odpověď, kterou oceníš ✓, se stane vzorem pro danou oblast a AVIOR ji při podobné otázce napodobí (few-shot) |
| **3. LoRA doladění vah modelu** | zdarma GPU (Kaggle/Colab) → adaptér do telefonu | skutečný trénink: tvoje oceněné odpovědi + sada dovedností (`skills.jsonl`) |

Proč ne trénink přímo v telefonu: trénování v llama.cpp je zatím experimentální, jen ve FP32
a i 1B model potřebuje kolem 24 GB paměti – na telefonu s 8 GB RAM to nejde. LoRA adaptér se proto
trénuje na zdarma GPU a v telefonu se jen **načte** (llama.cpp `llama_adapter_lora_init`), což je rychlé.

## Postup (asi 20–40 minut, zdarma)

1. V aplikaci: hodnoť dobré odpovědi ✓ (čím víc, tím lépe; stačí i jen vestavěná sada dovedností).
2. **Moduly → Doladění → Exportovat trénovací data** → soubor `Stažené/AVIOR/avior-trenink.jsonl`.
3. Na [kaggle.com](https://www.kaggle.com) založ Notebook → *Accelerator: GPU T4*, *Internet: On*.
   Nahraj `avior-trenink.jsonl` a `train_lora.py` a spusť:
   ```
   !pip install -q unsloth
   !git clone --depth 1 https://github.com/ggml-org/llama.cpp
   !pip install -q -r llama.cpp/requirements/requirements-convert_lora_to_gguf.txt
   !python train_lora.py --data avior-trenink.jsonl --base unsloth/gemma-4-E4B-it
   ```
4. Stáhni `avior-lora.gguf`, v aplikaci **Moduly → Doladění → Nahrát adaptér**. Zapne se automaticky;
   sílu doladění nastavíš posuvníkem (100 % = plná).

**Adaptér musí odpovídat mozku v telefonu** (`--base`): Gemma 4 E4B → `unsloth/gemma-4-E4B-it`,
Gemma 4 E2B → `unsloth/gemma-4-E2B-it`. Jinak ho aplikace odmítne nasadit a vrátí se k základnímu mozku.

`build_skills_dataset.py` znovu vygeneruje `skills.jsonl` (≈90 ukázek: protokol agenta, výroba APK
a dokumentů, výpočty STEP-0, offline knihovna, styl RANDY, poctivé sebepoznání). Všechna čísla
v ukázkách počítá Python, ukázkové aplikace jsou funkční (ověřeno v prohlížeči).
