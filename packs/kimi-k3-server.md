# Kimi K3 a vlastní AI server

## Co je Kimi K3
Kimi K3 je otevřený model společnosti Moonshot AI (2026), mixture-of-experts s přibližně 2,8 bilionu parametrů, z nichž je na token aktivních zhruba 104 miliard, a kontextem až 1 milion tokenů. Váhy jsou zveřejněné; i nejmenší komunitní kvantizace GGUF mají stovky GB (např. ~594 GB), takže model nelze spustit v telefonu.

## Kimi K3 online
Oficiální API je OpenAI-kompatibilní: základní adresa https://api.moonshot.ai/v1, model kimi-k3, endpoint /chat/completions. Klíč se vytváří na platform.moonshot.ai. K3 vždy přemýšlí; hloubku řídí parametr reasoning_effort (low, high, max). Teplotu určuje platforma.

## Kimi K3 offline na vlastním serveru
Na stroji s dostatkem paměti spusť OpenAI-kompatibilní server, například llama.cpp: llama-server -m <první-soubor-kvantizace>.gguf --host 0.0.0.0 --port 8080 --jinja. Alternativy: vLLM nebo SGLang. V AVIOR zadej adresu http://IP-serveru:8080/v1 a název modelu. Nešifrované http:// je povolené jen v lokální síti (192.168.x.x, 10.x.x.x, *.local, Tailscale 100.64.0.0/10); přes internet používej https.

## Mozek v telefonu
V telefonu běží menší modely přes llama.cpp: OMEGA Lite (Qwen3.5 2B, 1,28 GB) pro 4–6 GB RAM, OMEGA Core (Qwen3.5 4B, 2,74 GB) pro 8+ GB RAM, AXIOM Classic (Qwen3 1.7B Q8, původní mozek AXIOM 2.1). Každý stažený mozek se ověřuje SHA-256 a stahování po výpadku naváže.

## Claude
Claude se připojuje přes oficiální Anthropic API (klíč z console.anthropic.com). Výchozí model claude-opus-5-5 s adaptivním přemýšlením; volitelně webové vyhledávání s citacemi. V Konziliu může Claude sloužit jako rozhodčí mezi odpověďmi více mozků.
