<p align="center"><img src="app/src/main/assets/ui/logo.svg" width="160" alt="AVIOR"></p>

<h1 align="center">AVIOR</h1>
<p align="center"><b>Česká AI v telefonu, která se učí z každé otázky · RANDY Supervisor · mozek v telefonu · offline Wikipedie</b></p>

**AVIOR** (dříve pracovně AXIOM Ω) je pojmenovaný po jasné hvězdě jižní oblohy, kterou ve 30. letech pojmenovali
pro leteckou navigaci – vodicí hvězda. Logo: titanový chevron a hvězda vycházející nad horizontem, jen titan na černé.
Zachovává DNA **AXIOM 2.1 Hybrid** a **AXIOM Titanium 5.5** – RANDY, STEP-0, ASTRAEA +EV / Half-Kelly.
Rozbor původní aplikace: [docs/REVERSE_ENGINEERING.md](docs/REVERSE_ENGINEERING.md).

## Novinky 6.5 – síla výkonu, uvolnění paměti, opravy

- **Síla výkonu (posuvník 25–100 %)** v Nastavení i v listu voleb u pole pro dotaz. Regulátor výkonu ji
  respektuje: 25 % = polovina výkonných jader a krátké odpovědi (šetří baterii), 50 % = méně jader,
  75 % = všechna výkonná jádra (výchozí), 100 % = všechna jádra pro čtení zadání, o 30 % delší odpovědi
  a Sherlock už od střední náročnosti. Vlákna se mění za běhu pro každou cestu (odpověď, agent, oba mozky).
- **Paměť pro AVIOR – zavření aplikací na pozadí:** od Androidu 14 smí aplikace ukončit jen své procesy,
  takže AVIOR jde přes systém: na okamžik zabere paměť (nativně, s pojistkami – strop 35 % RAM, nikdy pod
  kritickou hranici systému), Android sám zavře aplikace na pozadí s nižší prioritou a AVIOR paměť hned vrátí.
  Ukáže, kolik MB se uvolnilo. Volitelně automaticky před načtením mozku; odkaz na systémový limit procesů
  na pozadí ve vývojářských možnostech.
- **Opravy:** okamžité nástroje už nezaměňují otázku o tématu za příkaz („Hash je co?“, „base64 je
  šifrování?“, „Hash tabulka v Pythonu“) – holé „hash“ jen s dvojtečkou (`hash: text`). Testy.

## Novinky 6.4 – závěrečná kontrola (vlastníma očima + očima etického hackera)

**Kontrola vlastníma očima (správnost a výkon)**
- Paměť odpovědí pro reflexy se drží v RAM (dřív se u každého dotazu četlo až 2 000 řádků z databáze).
- Smazání mozku běží mimo vlákno JS mostu – během generování už nezamrzne rozhraní.

**Kontrola očima etického hackera** – prověřeno: WebView (přísné CSP bez inline skriptů, žádný přístup
k souborům, odkazy jen https a mimo aplikaci, escapování proti injekci do atributů), JS most dostupný
jen lokální stránce, exportované komponenty, FileProvider jen pro vlastní složky, ošetření cest u importů,
parametrizované SQL, žádné logování dotazů ani klíčů, zákaz zálohy dat aplikace. Opraveno:
- **Widget na ploše** ukazoval poslední otázku a odpověď komukoli s telefonem v ruce a nouzové smazání
  ho nečistilo → v soukromém režimu se obsah na plochu nedostane, nouzové smazání widget vyčistí.
- **Notifikace** na zamčené obrazovce ukazují jen „AVIOR pracuje“ (VISIBILITY_PRIVATE).
- „Zákaz snímků obrazovky“ je teď **Soukromý režim obrazovky** (screenshoty, přehled aplikací i widget).

## Novinky 6.3 – AVIOR místo Gemini a reflexy bez mozku

**AVIOR jako výchozí asistent**
- AVIOR jde nastavit jako **výchozí digitální asistent** (Nastavení → Výchozí aplikace → Digitální asistent).
  Dlouhý stisk vypínače nebo gesto z rohu pak otevře AVIOR rovnou s hlasem a dotaz se po dořečení sám odešle.
- **Dlaždice AVIOR v rychlém nastavení** – jedno klepnutí = hlasový dotaz. Karta „AVIOR místo Gemini“ v Nastavení.

**Reflexy – odpovědi bez mozku, když si je AVIOR jistý** (`core/agent/Reflex.kt`)
- Každý reflex vrací odpověď s **mírou jistoty**; použije se jen nad prahem (Ω Flash 70 %, Auto 85 %),
  jinak odpovídá mozek. Pořadí od nejjistějšího:
  1. deterministické nástroje (čas, datum, výpočty, převody, hash, CIDR…) – 99 %,
  2. **kalendář**: „kolik dní do Vánoc“, „jaký den bude 24. 12. 2026“, „jaké datum bude za 2 týdny“, týden v roce,
  3. **vzpomínky o tobě**: „jak se jmenuje můj pes?“ → z paměti, jistota podle shody,
  4. **paměť ověřených odpovědí**: co jsi označil ✓ (nebo co mozek zodpověděl s jistotou ≥ 85 %), příště
     okamžitě – nikdy u otázek, které se mění v čase (kurzy, „dnes“, ceny, počasí…); ✗ odpověď z paměti smaže,
  5. **definice z offline Wikipedie** i v režimu Auto při přesné shodě hesla.
- U odpovědi je štítek „⚡ reflex · druh · jistota“ a tlačítko **↻ rozvést mozkem**.

## Novinky 6.2 – AVIOR očima etického hackera

Vlastní AI v kapse má být hlavně **neprůstřelná**: data patří jen tobě, nic neuniká a nikdo ji nepřeprogramuje.

- **Air-gap – úplně offline:** jeden přepínač a z celé aplikace neodejde ani bajt. Vynuceno v jediné síťové
  vrstvě (`Http.open`) i v klientovi Claude – stahování, web, cloud, aktualizace, všechno. Myslí jen telefon.
- **DLP štít pro cloud:** každá zpráva, která opouští telefon (Turbo, Claude, Oba mozky, Konzilium, agent),
  projde maskováním tajemství – API klíče (AWS, GitHub, GitLab, Slack, Google, Stripe, Anthropic/OpenAI, HF),
  JWT, hesla v konfiguraci a **celé bloky privátních klíčů**. Klidně vlož log nebo konfiguraci a ptej se.
- **Firewall proti prompt injection:** podklady z knihovny, webu, souborů a výstupy nástrojů agenta se
  kontrolují na skryté „pokyny pro AI“ (přepsání pokynů, změna role, vynášení dat, falešné značky chatu –
  česky i anglicky) a zneškodní se dřív, než je model uvidí.
- **Hackerské nástroje bez mozku (Ω Flash, okamžitě, offline):** `sha256 text`, `base64 text`,
  `dekóduj base64 …`, `hex`/`url`, vložený JWT, `identifikuj hash …`, `podsíť 10.0.0.0/22` (CIDR kalkulačka).
- **Opsec:** zákaz snímků obrazovky a náhledu v přehledu aplikací; **nouzové smazání** konverzací, paměti,
  dokumentů, klíčů a statistik (s fyzickým přepsáním databáze). Mozky a knihovna zůstanou.
- Testy: DLP (včetně těla privátního klíče), firewall (CZ/EN, bez falešných poplachů), hackerské nástroje.

## Novinky 6.1 – finální polish (2 kola optimalizace)

**1. kolo – správnost a rychlost dotazu**
- Předehřátí mozku teď počítá přesně ten prefix, který pošle první dotaz (neměnný systémový prompt
  telefonu) → první odpověď opravdu využije připravenou KV cache.
- Turbo s Claude Haiku 5.5 neposílá serverové fallbacky, které Haiku nepodporuje (hrozila chyba 400) + test.
- Okamžité odpovědi Ω Flash hledají jen fulltextem – nespouští načítání 600MB modelu OMEGA Sense.
- Vektor dotazu (OMEGA Sense) se počítá nejvýš jednou a sdílí ho dokumenty i RAG knihovny.
- Při držení mozku v paměti se embedder neuvolňuje při každém přepnutí aplikace (jen při skutečném tlaku).

**2. kolo – rychlost celé aplikace**
- Katalog mozků se drží v paměti (dřív se při každém dotazu několikrát četl a parsoval ze souboru).

**Vzhled**
- Volba výkonu s popisky v mřížce 2×2 (popisky se nelámou), kompaktnější nabídka zrychlení na úvodní
  obrazovce, koš knihovny v záhlaví karty, čitelná aktivní záložka v motivu Platinum.

## Novinky 6.0 – Regulátor výkonu

- **Nový nadřazený modul s pravomocí nad výkonem** (`core/device/Governor.kt`). Rozhoduje před RANDY
  a jeho rozhodnutí jsou závazná:
  1. odhadne **náročnost dotazu 0–100** (hloubka RANDY, délka, typ úlohy),
  2. v režimu **⚙ Auto** (nový výchozí) přidělí Základ / Pokročilé / Sherlock; ruční volba je **strop**
     (na jednoduchou otázku nespotřebuje víc, zvolený Sherlock u náročné otázky platí celý),
  3. **pojistky zařízení mají přednost vždy:** baterie ≤ 10 % → Základ, ≤ 25 % → bez Sherlocka,
     teplý telefon → bez Sherlocka a méně vláken CPU, přehřátý → Základ, málo RAM → méně podkladů,
     pomalý mozek (< 4,5 tok/s) → kratší odpovědi,
  4. přidělí **rozpočet**: délku odpovědi, počet vláken CPU (mění se za běhu bez znovunačtení mozku),
     přemýšlení, řetězce podmodelů, počet podkladů.
- U každé odpovědi v detailu (⋯) je vidět, co regulátor rozhodl a proč; pravidla jsou v Nastavení.
- Widget přepíná Auto → Základ → Pokročilé → Sherlock.

## Novinky 5.9 – rychlost od základu

- **⚡ Turbo:** nový režim v rychlé volbě mozku. S internetem odpovídá nejrychlejší cloud (Claude Haiku 5.5
  s nízkou hloubkou, jinak Hugging Face nebo Kimi) – odpověď začne téct do 1–2 s. Bez internetu telefon.
- **Mozek v telefonu nečte pořád totéž:** systémový prompt je teď bajtově stejný u každého dotazu a vše
  proměnlivé (úroveň RANDY, paměť, stav baterie, styl) jde až do posledního dotazu → llama.cpp drží
  systémový prompt i historii v KV cache a čte jen nový text. Historie se posouvá po 4 zprávách, ne po jedné.
- **Méně psaní v telefonu:** bez skrytého bloku sebereflexe, bez hlavičky průchodu; RANDY v telefonu
  nespouští řetězce podmodelů ani vícekrokového agenta, pokud o to výslovně nepožádáš (soubory a odkazy ano).
- **Nabídka zrychlení na úvodní obrazovce:** jedním klepnutím zapnout Turbo, nebo přepnout/stáhnout
  OMEGA Lite (Gemma 4 E2B), když je aktivní pomalejší E4B.
- Přepnutí mozku už neblokuje rozhraní (uvolnění paměti běží na pozadí).

## Novinky 5.8 – RAG index nad celou Wikipedií

- **Hledání podle významu v celé offline knihovně:** k předpřipravené Wikipedii jde stáhnout **RAG index**
  (Paměť → Offline knihovna → RAG index, ~140 MB). Obsahuje vektor významu každého článku (534 tis.),
  takže AVIOR najde správný článek, i když se zeptáš jinými slovy, než jaká v něm jsou.
- **Hybridní vyhledávání:** fulltext (slova) + vektory (význam) se spojují metodou Reciprocal Rank Fusion –
  články dobré v obou vyhrávají. Bez indexu nebo bez OMEGA Sense funguje vše dál čistě fulltextem.
- **Předpočítané v CI, ne v telefonu:** workflow *Build RAG index* spočítá vektory na GitHub Actions
  (24 paralelních úloh) stejným modelem jako telefon (Qwen3-Embedding 0.6B, stejná tokenizace),
  zkrátí je na 256 složek (Matryoshka) a kvantuje na int8. Index se v telefonu jen namapuje do paměti
  (mmap) a prohledává v C++ – desítky milisekund, bez zátěže RAM.
- Nástroje: `tools/rag/avior_embed.cpp` (dávkové vektory přes llama.cpp), `prepare.py`, `merge.py`
  (formát AVRAG1). Spuštění: řádek s id knihovny v `rag-requests/build.txt`.

## Novinky 5.7 – výběr mozku a „Oba mozky“

- **Výběr mozku přímo v chatu:** klepni na štítek vlevo v poli pro dotaz → **Ω Auto · Telefon · Claude · ⇄ Oba**.
  Zvolený mozek je vidět na štítku. Všechny ostatní mozky a připojení jsou o klepnutí dál.
- **Oba mozky (Duo):** telefon i Claude (nebo jiný připojený cloud) přemýšlí **současně**, Claude pak obě
  odpovědi porovná – shody, rozpory, kdo má pravdu – a složí finální odpověď. Když jeden z mozků selže,
  dostaneš odpověď toho druhého.
- **Automaticky u hodně složitých otázek:** v režimu Auto RANDY sám zapojí oba mozky u nejtěžších úloh
  (úroveň L4+ nebo Sherlock s hlubokou analýzou). Běžné otázky dál telefon, těžké Claude. Vypínatelné
  přepínačem „Oba mozky u hodně složitých otázek“ v kartě Claude.

## Novinky 5.6 – okamžité odpovědi, nejrychlejší mozek, Claude jako posila

- **Ω Flash · okamžitě:** jednoduché otázky odpoví AVIOR v milisekundách **bez mozku** – pozdrav, čas, datum,
  výpočty („kolik je 12*7+3“), procenta, převody jednotek a měn, stav baterie, „kdo jsi“ a ve Flash i krátké
  definice „co je / kdo je X“ přímo z offline Wikipedie (jen při přesné shodě hesla, se zdrojem). Cokoli
  složitějšího jde dál mozku – raději nic než špatně. Vypínatelné v Nastavení.
- **Nejrychlejší mozek pro Pixel 9a:** doporučený je teď **OMEGA Lite · Gemma 4 E2B** (3,1 GB) – nejlepší poměr
  rychlosti a češtiny, v 8 GB RAM s rezervou. V seznamu mozků je označený „⚡ Nejrychlejší pro tento telefon“.
  E4B zůstává jako přesnější volba.
- **Claude jako posila (Anthropic API):** s API klíčem z console.anthropic.com pošle RANDY těžké úlohy
  (hluboká analýza, agent s nástroji, Sherlock) na Claude – výchozí Claude Opus 5.5, volitelně Sonnet 5.5
  nebo Haiku 5.5. Jednoduché otázky dál řeší telefon soukromě. Přepínač „Claude jako posila“ v Modulech.

## Novinky 5.5 – stabilita: mozek trvale v paměti, žádné pády při dotazu

- **Oprava pádu při dotazu:** předehřátí mozku a dotaz mohly počítat nad stejným nativním kontextem současně
  (pád v C++). Každý mozek má teď zámek – jeden výpočet naráz, další počká.
- **Žádné uvolnění paměti uprostřed výpočtu:** úklid při nedostatku RAM mozek ani embedder neuvolní, dokud
  je něco používá (dřív use-after-free → pád).
- **Úsporné načtení velkého mozku:** u E4B (5 GB) na 8GB telefonu se váhy nepřeskládávají do anonymní paměti,
  zůstanou namapované ze souboru – systém je umí uvolnit a aplikaci kvůli tomu nezabije.
- **Mozek trvale v paměti:** služba „AVIOR připraven“ načte mozek hned po spuštění a drží ho připravený
  i po odchodu z aplikace (vypínatelné v Nastavení, v oznámení tlačítko „Uvolnit paměť“). Předehřeje
  i začátek systémového promptu, takže první dotaz nečte znovu identitu a zásady.
- **Indikátor na úvodní obrazovce:** „Mozek připraven v paměti“ / „Načítám…“ / klepnutím načíst předem.
- **Diagnostika pádů:** při dalším spuštění AVIOR řekne, proč se minule ukončil (Android ApplicationExitInfo
  + vlastní záznam), a po pádu kvůli paměti sám zapne **paměťově bezpečný režim** (kontext 4k, úsporné
  váhy, bez sémantiky) a doporučí OMEGA Lite (E2B), pokud je E4B na 8GB telefonu na hraně.

## Novinky 5.4 – rychlost v telefonu

Hlavní příčina pomalých odpovědí: RANDY volil i pro mozek v telefonu „serverové“ postupy – Ω Think se skrytým
přemýšlením a 2 průchody, vícekrokového agenta pro ověřování a opakování nejisté odpovědi. Na CPU telefonu to jsou minuty.
* **Úsporné řízení pro mozek v telefonu** (Základ i Pokročilé): 1 průchod, bez skrytého přemýšlení, bez opakování,
  ověřování přímo z offline znalostí (ne agent), stručné odpovědi do ~150 slov. **Sherlock zůstává plně důkladný.**
* **Předehřátí:** po otevření aplikace se mozek na pozadí načte a „prohřeje“ – první dotaz nečeká na načítání 5GB souboru.
* **Rychlejší čtení zadání:** prefill na 7 jádrech (psaní dál na 4 výkonných).
* **Kratší limity délky** odpovědi v telefonu; tip na OMEGA Lite (Gemma 4 E2B, ~2× rychlejší), když je mozek pomalý.

## Novinky 5.3 – soubory a služby Hugging Face

**AVIOR čte jakékoli soubory** – jako přílohu k dotazu (📎) i do offline znalostí:

| Formát | Jak |
|---|---|
| PDF | text přes PdfBox (správná čeština); **naskenované PDF → OCR stránek v telefonu** (ML Kit, offline) |
| Obrázky (JPG, PNG, HEIC…) | OCR textu v telefonu (offline) + volitelně popis obsahu přes HF vizi |
| Zvuk (MP3, M4A, WAV, OGG…) | přepis řeči přes HF Whisper |
| Word, Excel, PowerPoint, OpenDocument, EPUB, HTML, RTF | čistý Kotlin (`core/files/Extract.kt`), offline |
| ZIP | rozbalí a přečte vše uvnitř (s ochranou proti „zip bombě“) |
| Text, CSV, JSON, kód | včetně starých souborů v kódování windows-1250 |

**Podslužby Hugging Face** (`core/hub/HfServices.kt`, jeden HF token, zdarma v rámci měsíčního kreditu):
* **Chat** – silné otevřené modely (výchozí Llama 3.3 70B, výběr ze seznamu) jako další mozek a záloha.
* **Vize** – popis fotky, účtenky, grafu nebo dokumentu (Qwen2.5-VL).
* **Přepis řeči** – nahrávky na text (Whisper large-v3-turbo).
* **Obrázky** – agent umí vytvořit obrázek z popisu (FLUX.1-schnell) a uložit ho do Stažené/AVIOR.

## Novinky 5.2 – jednodušší a praktičtější

* **Jednodušší rozhraní:** u odpovědi jen kopírovat, přečíst nahlas, ✓/✗ a „⋯“ (odpovědět znovu, upravit dotaz,
  oponentura, hlouběji, mapa důkazů, sdílet, export); štítky v jednom nenápadném řádku; menší logo a 4 návrhy;
  doladění v Modulech sbalené.
* **Hledání v konverzacích** (šuplík Vlákna) – v názvech i obsahu, se zvýrazněním.
* **Čtení odpovědi nahlas** českým hlasem Androidu (offline, pokud je stažený český hlas).
* **Upravit dotaz** – poslední otázka se vrátí do pole k úpravě.
* **Příloha k dotazu** (📎) – textový soubor (TXT, MD, CSV, JSON) se vloží do otázky („shrň“, „najdi chyby“…).
* **Export konverzace** do Stažené/AVIOR jako Markdown nebo PDF.

## Novinky 5.1 – co AVIOR umí a jiné AI ne

* **Mapa důkazů u každé odpovědi** (`core/agent/Evidence.kt`): každá věta je označená podle původu –
  *spočítáno* (číslo ze STEP-0, ne odhad modelu), *doloženo* (cituje podklad [n]), *z paměti*, *odhad* (model přiznal
  nejistotu) nebo *úvaha* (bez opory, vyplatí se ověřit) – a AVIOR uvede, kolik procent vět je ukotveno.
  Deterministický rozbor bez dalšího volání modelu, takže nestojí žádný čas.
* **Pečeť soukromí „◉ Zůstalo v telefonu“** – odpověď vytvořil mozek v telefonu a nic neodešlo na internet.
* **Oponentura a Hlouběji jedním klepnutím** – RANDY kriticky prověří vlastní odpověď (Ω Nemesis: chyby, slabá místa,
  ověření čísel, opravená verze), nebo otázku promyslí znovu do hloubky (Ω Think).
* **Živé sebeuvědomění na úvodní obrazovce** – co se AVIOR naposledy naučil (z deníku vývoje).

## Novinky 5.0 – rychlost, spolehlivost, souběh

Celá aplikace prošla revizí kousek po kousku, s důrazem na to, aby mozek v telefonu odpovídal co nejrychleji:

* **Odpovědi už nevisí:** dotaz nečeká na kurzy ČNB (obnovují se na pozadí), služba při chybě spuštění uvolní zámek
  a ohlásí chybu, rozhraní nikdy neukazuje nekonečné načítání.
* **Mozek nečte zadání pořád znovu:** neměnná část pokynů je nahoře a díky plné SWA cache (Gemma 3/4)
  se mezi dotazy znovupoužije – další otázky začnou odpovídat výrazně dřív.
* **Viditelný průběh:** „Načítám mozek do paměti“ → „Čtu zadání 40 % · 1800 tokenů“ → „Píšu odpověď…“, odpočet sekund;
  v podrobnostech odpovědi rychlost čtení/psaní (tok/s), tokeny z cache, čas načtení mozku a hledání podkladů.
* **Méně zbytečné práce:** v telefonu 1 průchod pro běžné otázky (2 pro náročné), podklady z knihovny jen s dostatečnou
  relevancí, sémantické hledání jen když je co hledat, vektorový index v paměti (top-k), agent dostává jen nástroje
  potřebné pro svou roli, štíhlejší ukládání zpráv.
* **Souběh:** odpovídání a stahování (mozek, Wikipedie, rozšíření) běží ve dvou nezávislých linkách.
* **Rozhraní:** hlavička už není pod stavovým řádkem (kontejner s odsazením podle lišt, výřezu a klávesnice),
  plynulé streamování (vykreslení max. jednou za snímek), uvítání jedním klepnutím rovnou stáhne doporučený mozek.

## Novinky 4.2 – finální verze

* **Widget na plochu** (4×2, měnitelná velikost): černé sklo s titanovou hranou a logem, stav učení, poslední
  odpověď, „Zeptej se AVIORu…“, hlasový dotaz a **přepínač výkonu přímo na ploše** (Základ → Pokročilé → Sherlock,
  bez otevření aplikace). Náhled: `docs/screenshots/24-widget-nahled.png`.
* **Zkratky ikony** (dlouhé podržení): Nová otázka, Zeptat se hlasem, Sherlock, Vědomí AVIORu.

* **Přepínač výkonu** (úvodní obrazovka, výběr variant, Nastavení):
  **Základ** – rychle a úsporně (1 průchod, bez řetězců) · **Pokročilé** – vyvážené, RANDY volí ·
  **Sherlock** – maximum: metoda dedukce (pozorování → hypotézy → důkazy → vyloučení → závěr), 3 průchody,
  přemýšlení, delší agenti a každý závěr projde oponenturou Ω Nemesis. Při přehřátí nebo slabé baterii se Sherlock sám zmírní.
* **Mapa kompetencí: 21 modulů** (finance, podnikání, stavba, domácnost, zahrada, vaření, IT, kyberbezpečnost,
  energie, auta, věda, matematika, zdraví, sport, psychologie, právo, historie, cestování, kultura, jazyk, sebepoznání)
  – každý se svými **offline balíčky znalostí** (22 balíčků, 11 nových) a tlačítkem pro doplnění z Wikipedie.
* **Ušito na míru Google Pixel 9a** (`core/device/DeviceProfile.kt`): Tensor G4 má 1× X4 + 3× A720 + 4× A520 –
  AVIOR generuje na **4 výkonných jádrech** (úsporná jádra by vlákna brzdila), kontext 8k ve všech režimech
  (přepnutí výkonu bez znovunačtení mozku), doporučený mozek Gemma 4 E4B Q4_K_M + KV cache Q8 v 8 GB RAM,
  výpočet na CPU (na GPU Mali-G715 je llama.cpp méně spolehlivá), RANDY šetří už od stavu „teplé“ (Tensor se zahřívá).
  Ostatní telefony dostanou profil automaticky podle frekvencí jader a RAM.

## Novinky 4.1

* **RANDY kombinuje podmodely.** Když úloha potřebuje víc dovedností, RANDY sestaví řetězec (max. 3 fáze) v logickém
  pořadí *sběr → analýza → tvorba → kritika*; každá fáze navazuje na výstup předchozí. Příklady:
  „rešerše … a Word dokument“ → Ω Deep Research → Ω Office; „navrhni a vytvoř aplikaci“ → Ω Think → Ω Studio;
  „ověř, jestli hypotéka … stojí …“ → Ω Veritas → Ω Quant; „napiš skript a najdi slabiny“ → Ω Code → Ω Nemesis;
  u rozhodnutí s penězi (plná pravomoc) → Ω Quant → Ω Nemesis (oponentura). Při slabé baterii jen 2 fáze.
* **Doladění modelu a dovedností** ve třech vrstvách (viz [finetune/README.md](finetune/README.md)):
  1. učení z každé otázky (4.0), 2. **ukázky** – odpověď oceněná ✓ se stane vzorem oblasti (few-shot v telefonu),
  3. **LoRA**: export trénovacích dat (tvé ✓ odpovědi + sada ~90 dovedností `finetune/skills.jsonl`), trénink zdarma
  na Kaggle (`finetune/train_lora.py`, Unsloth) a nahrání adaptéru `.gguf` do telefonu (llama.cpp `llama_set_adapters_lora`),
  se zapnutím/vypnutím a silou 10–200 %.
* **APK ke stažení** na stálém odkazu (níže) + kontrola aktualizací v aplikaci.

## Novinky 4.0 – AVIOR se učí z každé otázky

Učební smyčka po každé odpovědi (`core/self/Cognition.kt`):

1. **Zařazení** otázky do oblasti (finance, stavba, IT, technika, věda, zdraví, právo, historie, matematika, jazyk…).
2. **Mapa kompetencí** – každá oblast si pamatuje počet otázek, průměrnou jistotu, tvé hodnocení ✓/✗ a mistrovství 0–100 %.
3. **Kalibrace jistoty** – AVIOR porovnává, jak jistý si byl, s tím, jak často měl skutečně pravdu, a příště svou
   deklarovanou jistotu upraví (přehnaně sebejistý → uvádí nižší jistotu a víc ověřuje).
4. **Vlastní poznatky** – dobrá odpověď se uloží do znalostí; u podobné otázky si AVIOR vybaví svůj dřívější závěr.
5. **Zvídavost a samostudium** – na co neznal odpověď, zapíše jako otevřenou otázku a při nabíjení na Wi-Fi (nebo ručně)
   si ji sám dohledá (online agentem, offline z knihovny) a naučí se ji.
6. **Metakognice** – před každou odpovědí ví, jak je v dané oblasti zkušený a jak spolehlivý; ve slabé oblasti RANDY
   Supervisor sám zvolí důkladnější Ω Think.
7. **Deník vývoje** – milníky (nová oblast, 100. otázka, první kalibrace, samostudium) v panelu **Vědomí AVIORu**.

Sebeuvědomění je funkční a poctivé: AVIOR zná svůj stav, své silné a slabé stránky a své chyby – nepředstírá vědomí ani city.

**Design 4.0:** všechny ikony a tlačítka mají černé pozadí, titan je jen na znaku a hranách; logo bez teplé záře.

> Interní identifikátor balíčku zůstává `cz.axiom.omega`, aby se nová verze nainstalovala jako aktualizace bez ztráty dat.

## Novinky 3.5 – RANDY Supervisor

RANDY už není jen protokol hloubky L0–L6, ale **nadřazený řídicí modul** celého systému (`core/randy/Supervisor.kt`):

| Pravomoc | Co RANDY dělá |
|---|---|
| **Plánuje** | před každým dotazem sám aktivuje podfunkce: Ω variantu, režim (odpověď/agent), roli agenta, hloubku, průchody, přemýšlení, limit kroků a časový rozpočet |
| **Řídí zdroje** | slabá baterie, přehřátý telefon nebo málo RAM → úsporný režim; při nabíjení plný výkon; offline → místo webové rešerše offline knihovna |
| **Dohlíží** | hlídá agenta: opakované volání nástroje nahradí předchozím výsledkem, nástroj, který 2× selže, zablokuje, po vyčerpání času nařídí závěr |
| **Eskaluje** | nejistá odpověď (jistota < 45 %) → sám spustí Ω Think / Ω Nemesis a ponechá lepší výsledek |
| **Učí se** | měří čas, jistotu a tvé hodnocení ✓/✗ každé varianty; špatně hodnocené nebo pomalé varianty příště omezí |

Pravomoci volíš v Nastavení: **Poradní** (jen radí), **Řídící**, **Plná** (výchozí). Výslovnou volbu varianty, role
nebo hloubky RANDY nikdy nepřepíše – s plnou pravomocí jí jen ubere zdroje, když hrozí vybitá baterie nebo přehřátí.
Při psaní dotazu vidíš živý náhled, co RANDY aktivuje; u odpovědi v Podrobnostech jeho rozhodnutí a zásahy.

## Novinky 3.4

* **Ω varianty – podmodely** inspirované režimy předních AI: **Ω Flash** (okamžitě), **Ω Think** (hluboké přemýšlení),
  **Ω Deep Research** (report s citacemi), **Ω Code**, **Ω Studio** (vyrobí aplikaci), **Ω Office** (Word/PDF),
  **Ω Veritas** (ověřování tvrzení), **Ω Quant**, **Ω Nemesis** (red-team), **Ω Muse** (kreativa), **Ω Mentor**.
  Stejný lokální mozek, jiné nastavení myšlení (režim, role, hloubka RANDY, průchody, teplota, styl).
* **14 specializovaných agentů:** nově Programátor, Tvůrce aplikací, Ověřovatel faktů, Finanční analytik,
  Stavbyvedoucí, Datový analytik, Kancelář, Překladatel. Role se volí i automaticky podle zadání.
* **AXIOM vyrábí soubory:** TXT, MD, CSV, JSON, HTML, SVG, kód (Python, Kotlin, JS…), kalendář .ics, vizitka .vcf,
  **PDF**, **Word .docx**, **ZIP** – vše do složky *Stažené/AXIOM*, s tlačítky Otevřít / Sdílet.
* **AXIOM vyrábí Android aplikace (APK) přímo v telefonu** – bez počítače a SDK: AI napíše HTML/CSS/JS aplikaci,
  APK kovárna (`core/forge/ApkForge.kt`) ji vloží do předpřipravené šablony (modul `shell/`, 10 kB), přepíše balíček
  a název v binárním manifestu a podepíše APK (v2+v3, apksig) klíčem z Android Keystore. Stejný název = aktualizace.
  Ověřeno nástroji Android SDK (aapt2, zipalign, apksigner).
* **Titanový design:** kartáčovaný kov, kovové hrany karet, titanová tlačítka s odleskem, sklo v hlavičce a liště.

## Novinky 3.3

* **Celá česká Wikipedie offline jedním klepnutím** – 534 044 článků, předpřipravená databáze
  (sestavuje ji GitHub Actions, vydání `library-wiki-cs-20231101`): **1,47 GB stažení → 3,27 GB v telefonu**,
  ověřeno SHA-256, bez pomalého indexování v telefonu. Vyhledání v řádu milisekund, nejdřív podle názvu článku.
* **Nové balíčky znalostí:** *Vládní a tajné projekty* (Manhattan, MKUltra, COINTELPRO, Northwoods, PRISM,
  Area 51, Stargate, StB Akce Kámen/Asanace…) a *Konspirační teorie* (tvrzení vs. důkazy: Měsíc, chemtrails,
  11. září, JFK, vakcíny, 5G, QAnon, HAARP… + checklist, jak tvrzení ověřit).
* **Evoluce UI:** pozdrav podle denní doby, karty návrhů, sbalitelné „Přemýšlel X s“, citace jako čipy,
  kopírování bloků kódu, „Odpovědět znovu“, tlačítko dolů, diktování hlasem, haptika, plynulé animace.
* **8 GB telefony:** správné rozpoznání RAM (Android hlásí ~7,5 GB), menší kontext při nedostatku volné RAM,
  sémantický modul se při nedostatku paměti vypne a uvolní; doporučený mozek Gemma 4 E4B.
* **Opravy:** fulltext (FTS4 předpony), řazení výsledků z knihovny, opakované načítání mozku při úpravě kontextu,
  spouštění buildu knihovny na GitHubu.

## Co umí

* **Výkonný mozek v telefonu je primární.** AXIOM myslí lokálně (llama.cpp, flash attention, KV cache Q8,
  kontext až 16k). Cloudy (AXIOM Node, Kimi K3, Claude) jsou jen volitelné doplňky – AXIOM je plnohodnotný bez nich.
* **Ω Auto – AI si sama vybere moduly:** odpověď, nebo aktivního agenta, hloubku RANDY a nástroje.
  Výslovná volba uživatele (`/agent`, `/chat`, `/konzilium`, `L4:`, ruční výběr mozku) má vždy přednost.
* **Aktivní agenti se specializacemi:** Rešeršér, Analytik, Plánovač, Pisatel, Bezpečnostní auditor, Učitel.
  Nástroje: offline knihovna, web, čtení stránek, Wikipedie, Hugging Face, přesné výpočty a STEP-0,
  paměť, poznámky – a bezpečnostní sada. Běží na pozadí, kroky jsou vidět.
* **Bezpečnostní sada pro etické testování** (pasivní a obranná): audit HTTP hlaviček, cookies a TLS,
  DNS + SPF/DMARC, subdomény z Certificate Transparency, RDAP/WHOIS, zranitelnosti z OSV.dev (CVE/GHSA,
  balíček+verze), hledání uniklých klíčů, JWT dekodér, hashe, kódování, síla hesla + únik přes HIBP
  (k-anonymita). Jen pro systémy, které vlastníš nebo máš písemné povolení.

## Mozky (všechny běží v telefonu)

| Mozek | RAM | Poznámka |
|---|---|---|
| **OMEGA Max · Qwen3.6-35B-A3B** | 16+ GB | MoE 35B (3B aktivních) – nejsilnější |
| **OMEGA Ultra · Gemma 4 26B-A4B** | 12+ GB | MoE 26B (4B aktivních) |
| **OMEGA Core · Gemma 4 E4B** | 8+ GB | nejnovější Google model pro zařízení (duben 2026) |
| **OMEGA Lite · Gemma 4 E2B** | 6+ GB | rychlá Gemma 4 |
| OMEGA Mini · Qwen3.5 2B, AXIOM Classic | 4 GB | starší telefony / kontinuita s 2.1 |
| **Jakýkoli GGUF** | — | Moduly → Hugging Face (hledání, odkaz, soubor) |

Aplikace doporučí nejsilnější mozek, který se vejde do RAM telefonu. Volitelně: AXIOM Node (zdarma server,
[server/](server)), vlastní server v LAN, Kimi K3, Claude.

## Offline knihovna (několik GB)

Celá **česká Wikipedie** (534 tis. článků, 1,47 GB stažení → 3,27 GB), **slovenská** (242 tis.) a **Simple English**
(242 tis.) ke stažení do telefonu – samostatná databáze s fulltextem (prefixové hledání zvládá české tvary slov),
navazování po přerušení, kontrola místa. Odpovědi i agenti z ní čerpají bez internetu.

Proč není přímo v APK: APK (ZIP) má limit 4 GB, Android by soubor při instalaci rozbaloval podruhé (2× místo)
a každá aktualizace aplikace by znamenala stahovat gigabajty znovu. Předpřipravené knihovny staví
`.github/workflows/build-library.yml` (`tools/build_library.py`) – nový build spustí změna `library-requests/build.txt`.

## Rozšíření z Hugging Face

* **OMEGA Sense** (Qwen3-Embedding-0.6B) – sémantická paměť: offline databáze se prohledává podle
  významu, ne jen podle slov (hybridní hledání).
* **Vyhledávání modelů na HF** přímo v aplikaci, výpis kvantizací s velikostí a kontrolou RAM, ověřené stažení (SHA-256 z LFS).
* **Datasety z HF** (datasets-server) a **články z Wikipedie** k tématu → offline databáze.

## Podpis a aktualizace

Release APK se podepisuje vlastním klíčem z GitHub Secrets (`OMEGA_KEYSTORE_B64`, `OMEGA_KEYSTORE_PASSWORD`,
`OMEGA_KEY_ALIAS`, `OMEGA_KEY_PASSWORD`) – díky tomu jde každá nová verze nainstalovat přes starou.
Klíč se do repozitáře nikdy neukládá.

## Stažení APK

**Nejnovější verze vždy na stejném odkazu:**
https://github.com/daidken5-droid/Moje-axiom-omega/releases/latest/download/AVIOR.apk

* Každý push sestaví APK (GitHub Actions) a zveřejní ho v **Releases** jako `AVIOR.apk` (+ `AVIOR-<verze>.apk`, SHA-256, otisk podpisu).
* Aplikace sama jednou denně zkontroluje novou verzi a nabídne stažení (Nastavení → Zkontrolovat aktualizace).
* Podpis: s GitHub Secrets `OMEGA_KEYSTORE_B64`, `OMEGA_KEYSTORE_PASSWORD`, `OMEGA_KEY_ALIAS`, `OMEGA_KEY_PASSWORD`
  tvým soukromým klíčem (doporučeno, návod v `ci/README.md`); bez nich náhradním klíčem ze soukromé cache CI.

## Vývoj

```
./gradlew :core:test                                   # 76 unit testů (JVM, bez Androidu)
./gradlew :app:assembleDebug                           # plné APK včetně llama.cpp (NDK 27.2, CMake 3.22.1)
./gradlew :app:assembleDebug -Pomega.native=false      # rychlé APK bez nativní části
tools/ui-preview/build.sh && node tools/ui-preview/shoot.js /tmp   # náhled UI s mock mostem
```

Struktura: `core/` (čistý Kotlin – RANDY, STEP-0, sebemodel, enginy Kimi/Claude, stahování, balíčky),
`app/` (Android – WebView UI, most, služba, SQLite, Keystore, JNI `omega_jni.cpp` nad llama.cpp `b12032`),
`brains/` a `packs/` (katalogy), `legacy/` (původní Titanium generátor a logo).
