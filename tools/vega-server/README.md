---
title: VEGA server
emoji: 🛰️
colorFrom: blue
colorTo: indigo
sdk: docker
app_port: 7860
pinned: false
---

# Vlastní AI server pro VEGA

Tvůj vlastní jazykový model: otevřený **Qwen3-4B-Instruct-2507** (licence Apache 2.0, kvantizace Q4_K_M, 2,5 GB) běží v **llama.cpp serveru** s OpenAI API.

- Podklady jdou jen na tvůj server, žádné komerční API se nevolá.
- VEGA ho zná jako **Vlastní model**. Přepínač „Pouze vlastní modely“ pak zajistí, že nic neodejde jinam.
- Server bez klíče odmítne každý požadavek (401).

## Dvě cesty, obě zdarma

| | Hugging Face Space (CPU basic) | Tvoje PC |
|---|---|---|
| Cena | 0 Kč | 0 Kč (elektřina) |
| Rychlost | ~4 slova/s (2 jádra, **naměřeno** stejným nastavením) | podle PC; 8 jader ≈ 2–3× rychleji |
| Dostupnost | odkudkoli přes https | jen doma ve Wi-Fi (http v lokální síti) |
| Omezení | po 48 h nečinnosti usne, první dotaz pak čeká 1–3 min na probuzení a stažení modelu | PC musí běžet |

### A) Hugging Face Space

1. Na huggingface.co si založ účet a dej **New Space**: SDK **Docker**, šablona Blank, hardware **CPU basic (free)**, viditelnost **Public**.
   - Private Space by chtěl vlastní přihlášení HF a VEGA by se k němu nedostala. Bez klíče VEGA_KEY ale server stejně nikomu neodpoví.
2. Nahraj do Space tyto soubory: `Dockerfile`, `start.sh`, `pair.py`, `README.md`.
   - Jde to přes web (Files → Add file → Upload) nebo `git push`.
3. **Settings → Variables and secrets → New secret**: název `VEGA_KEY`, hodnota aspoň 24 náhodných znaků.
   - Vygeneruješ je třeba příkazem `python3 -c "import secrets;print(secrets.token_urlsafe(32))"`.
4. Počkej na stav **Running** a otevři **Logs**. Uvidíš tam blok **PÁROVACÍ KÓD PRO VEGA**.

### B) Tvoje PC (Docker Desktop)

```
cd tools/vega-server
cp .env.example .env        # doplň VEGA_KEY a IP adresu PC v síti (VEGA_PUBLIC_URL)
docker compose up -d
docker compose logs -f      # počkej na „listening“, nahoře je párovací kód
```

- Bez Dockeru: stáhni llama.cpp a spusť `llama-server -hf unsloth/Qwen3-4B-Instruct-2507-GGUF:Q4_K_M --host 0.0.0.0 --port 7860 --api-key <VEGA_KEY> --jinja`.
- Kód vyrobíš příkazem `python3 pair.py http://<IP>:7860/v1 unsloth/Qwen3-4B-Instruct-2507-GGUF:Q4_K_M`.

## Spárování s VEGA

1. VEGA → **Nastavení → Vlastní model → Párovací kód**: vlož kód `VEGA-PAIR:…` a klepni na **Spárovat**.
   - VEGA vyplní adresu a model.
2. Do pole **Klíč serveru** vlož svůj `VEGA_KEY` a klepni na **Uložit a ověřit**.
   - VEGA klíč uloží šifrovaně (Android Keystore) a posílá ho jen na tuto adresu.
   - Když adresu změníš, klíč se smaže.
3. Volitelně zapni **Pouze vlastní modely**.

Klíč v párovacím kódu záměrně **není**, protože kód je vidět v logu.

## Výměna modelu

Proměnná `VEGA_MODEL_HF` přijímá libovolný GGUF z Hugging Face ve tvaru `repo:kvantizace`, `VEGA_ALIAS` je jméno, které uvidí VEGA. Na free CPU (16 GB RAM) se vejdou modely zhruba do 8B v Q4. Čím větší model, tím je pomalejší.

| Model | Velikost | Poznámka |
|---|---|---|
| `unsloth/Qwen3-4B-Instruct-2507-GGUF:Q4_K_M` | 2,5 GB | výchozí, dobrá čeština |
| `unsloth/Qwen3-1.7B-GGUF:Q4_K_M` | 1,1 GB | rychlejší, slabší |

## Co bylo ověřeno

Ověřeno 20. 11. 2026 na hostiteli, ne v telefonu, s obrazem `ghcr.io/ggml-org/llama.cpp:server-b12464` spuštěným přes chroot:

- `start.sh` vypíše párovací kód a spustí server s `--api-key`.
- Požadavek bez klíče vrátí 401, `/v1/models` vrátí `vega-qwen3-4b`.
- Klient VEGA (`OpenAiEngine`, test `OwnServerTest.liveServerAnswersInCzech`) dostal odpověď „Hlavní město Česka je Praha.“ (16 tokenů, 4,1 s).
- Na otázku „Kolik je 15 % z 2400 Kč?“ přišla správná česká odpověď (360 Kč s postupem). Generování běželo rychlostí 4,1 tokenu/s a vstup 43 tokenů/s, obojí na 2 vláknech.
- Stažení modelu přes `-hf` funguje.

NEOVĚŘENO:

- samotné nasazení na Hugging Face, protože k němu je potřeba tvůj účet;
- spojení z telefonu.
