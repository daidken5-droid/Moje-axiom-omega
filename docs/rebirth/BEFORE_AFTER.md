# BEFORE_AFTER – Delta IV (058c8cd, 1015) × OMEGA (1016)

| Oblast | Před | Po | Ověření |
|---|---|---|---|
| Incident Kimi (Kimi tokeny web_search) | `finish=done`, nástroj 0×, surový zápis zobrazen | nástroj 1×, výsledek modelu, COMPLETED, čistá odpověď | MOCK, obě verze spuštěny |
| Nativní tool_calls (stream) | zahozeny | složeny a provedeny | MOCK (SSE server) |
| Placené volání bez souhlasu | možné (FreeCloud fallback, Claude v auto výběru) | blokováno, strop, zápis útraty | UNIT |
| Max. délka dotazu | 32 000 znaků (chyba) | 120 000; větší než okno → po částech | MOCK |
| Zkrácená příloha | tiše | označeno v textu i v UI | STATIC |
| SSRF přes přesměrování | možné | blokováno | UNIT |
| Testy jádra / aplikace | 254 / 5 | 280 / 5 | UNIT |
| STEP-0 eval (5 061) | 0 falešných jistot | 0 falešných jistot | UNIT |
| Propustnost reflexu (StressTest) | ~13 300–14 400 /s | ~13 200–14 100 /s | JVM, ne telefon |
| Kvalita výzkumu, citací, latence modelů, RAM/start na Pixelu | — | — | NOT VERIFIED |
