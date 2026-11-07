# MODEL_BENCHMARK – stav: NEMĚŘENO proti skutečným API

V tomto prostředí nejsou API klíče poskytovatelů a cloudové prostředí nemá zaručený přístup k jejich API.
**Žádné číslo o kvalitě, latenci ani chybovosti modelů zde proto neuvádím** – bylo by vymyšlené.

Co je hotové a ověřené (MOCK):
- jednotné rozhraní nástrojů (OpenAI tools + skládání streamu) pro všechny OpenAI-kompatibilní poskytovatele,
- CostGuard (bezplatné první, placené jen s povolením a do stropu),
- samooprava modelu při HTTP 404 (výběr platného modelu z nabídky poskytovatele) – existovala dřív.

Kandidáti ze zadání (OpenRouter `nvidia/nemotron-3-ultra-550b-a55b:free`, Groq `openai/gpt-oss-120b`, Gemini Flash, Cerebras,
Cloudflare Workers AI, Chutes, Claude): **NOT VERIFIED** – ID modelů a podmínky je nutné ověřit s účtem; do automatického
výběru jsem nepřidal žádné neověřené ID.

Navržený postup měření (spustitelný až s klíči): sady jednoduchý chat / rešerše / dlouhé zadání / nástroje (AgentToolCallIncident
scénáře proti živému API) / ověřování tvrzení / dokumenty; metriky viz zadání 4.2.
