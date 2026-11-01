# AXIOM Node – zdarma cloudová výpočetní síla

AXIOM Node je llama.cpp server s OpenAI-kompatibilním API, ke kterému se AXIOM Ω připojí jako
k dalšímu mozku (Moduly → AXIOM Node). Router ho používá automaticky, když je online a úloha je náročná.

| Varianta | Zdarma | Výkon | Doporučený model |
|---|---|---|---|
| [Hugging Face Spaces](https://huggingface.co/new-space) (Docker, CPU basic) | ano, Space usíná po neaktivitě | 2 vCPU, 16 GB RAM | Gemma 4 E4B Q4_K_M |
| [Oracle Cloud Always Free](https://www.oracle.com/cloud/free/) (Ampere A1) | ano, trvale | 4 ARM jádra, 24 GB RAM | Qwen3.6-35B-A3B UD-IQ4_XS |

## Hugging Face Space
1. Vytvoř nový Space → SDK **Docker** → nahraj obsah složky `hf-space/`.
2. Settings → Secrets: `AXIOM_API_KEY` = dlouhé náhodné heslo (volitelně `AXIOM_MODEL`).
3. V aplikaci: adresa `https://<uživatel>-<space>.hf.space/v1`, model `axiom`, klíč `AXIOM_API_KEY`.

## Oracle Cloud
1. Vytvoř instanci Ampere A1 (4 OCPU, 24 GB, Ubuntu 24.04).
2. Spusť `oracle/setup.sh`, pak `sudo tailscale up`.
3. Nainstaluj Tailscale i do telefonu; v aplikaci zadej adresu, model a klíč, které skript vypíše.

## Cloudová databáze
Zálohu databáze AXIOM Ω (vlákna, paměť, znalosti) lze zdarma ukládat do **soukromého datasetu
na Hugging Face** (Paměť → Offline databáze → Cloud). Potřebuješ HF token s právem *write*.
