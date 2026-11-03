<p align="center"><img src="app/src/main/assets/ui/logo.svg" width="160" alt="AVIOR"></p>

<h1 align="center">AVIOR</h1>
<p align="center"><b>Česká AI v telefonu, která se učí z každé otázky · RANDY Supervisor · mozek v telefonu · offline Wikipedie</b></p>

**AVIOR** (dříve pracovně AXIOM Ω) je pojmenovaný po jasné hvězdě jižní oblohy, kterou ve 30. letech pojmenovali
pro leteckou navigaci – vodicí hvězda. Logo: titanový chevron a hvězda vycházející nad horizontem, jen titan na černé.
Zachovává DNA **AXIOM 2.1 Hybrid** a **AXIOM Titanium 5.5** – RANDY, STEP-0, ASTRAEA +EV / Half-Kelly.
Rozbor původní aplikace: [docs/REVERSE_ENGINEERING.md](docs/REVERSE_ENGINEERING.md).

## AVIOR Delta IV – PROOFCORE + Δ Lab

**Δ Lab (měřicí prostředí generací):** `core/src/test/.../DeltaLabTest.kt` a `core/src/test/resources/deltalab/`.
- **Sady otázek:**
  - vývojová sada `dev.tsv` (30 otázek, smí se na ní ladit),
  - **závěrečná** sada `final.tsv` (50 otázek, napsaná před změnami, neladí se na ní),
  - malá offline Wikipedie `kb.tsv`.
- **Měří:**
  - správně,
  - **chybně** (halucinace reflexu = odpověděl špatně, nebo tam, kde měl mlčet),
  - zdržel se (→ mozek),
  - latenci p50/p95,
  - kolik **vlastních neověřených odpovědí mozku** AVIOR vrátí jako odpověď „bez mozku“.
- **Brána:** proti `baseline.json` (předchozí generace) se nesmí zhoršit. Výsledek se v CI zapisuje do souhrnu běhu.

| Generace | final: správně | final: chybně | vráceno neověřených odpovědí mozku |
|---|---|---|---|
| Delta III (základna) | 46/50 | 1 | **6 z 8** |
| Delta IV | 49/50 | 0 | **0 z 8** |

Pozor: 3 z těch +3 na závěrečné sadě jsou oprava chyby, kterou závěrečná sada odhalila (desetinná čárka).
Pro tyto 3 položky už sada není „čistá“.

**PROOFCORE – jistota z důkazu, ne z přesvědčivého textu:**
- **Původ každé uložené odpovědi:**
  - ✓ uživatel,
  - **Wikipedie** (Strážce tvrzení kladně ověřil a nic nevyvrátil),
  - jen **model**.
- **Použití podle původu:**
  - ověřené odpovědi platí i pro přeformulovanou otázku,
  - neověřená odpověď mozku už nejde jako hotová odpověď v režimu Auto,
  - ve Flash se vrátí se štítkem „neověřeno“.
- **Revizní smlouva:** každá uložená odpověď má datum, kdy se znovu vyhodnotí, a důvod:
  - úřady, ceny a rekordy po 90 dnech,
  - neověřené odpovědi po 180 dnech,
  - ověřené nadčasové fakty po 3 letech.
  - Pak se nepoužije a otázka se zodpoví znovu.
- **Strážce faktů umí i kladný důkaz:** pod odpovědí může stát „Δ Ověřeno proti Wikipedii: Karel Gott – narození 14. července 1939“.
- **Poznámky agenta** se ukládají vždy jako „Poznámka AVIORu: …“. Model se tak nemůže vydávat za encyklopedii, protože WikiReflex i Strážce věří jen heslům „Wikipedie:“.

**Opravené chyby nalezené Δ Labem:**
- „3,5 krát 4“ dávalo 140, protože se ztratila desetinná čárka.
- Kalkulačka teď odmítne „3 5“ jako jedno číslo (mezera smí oddělovat jen tisíce).

## AVIOR Delta III

**Δ Srovnávač bez mozku (nová jedinečná funkce):** „Co je větší, Praha nebo Brno?“, „Kdo byl starší, Masaryk nebo
Beneš?“, „Která řeka je delší, Vltava nebo Labe?“, „Je Sněžka vyšší než Praděd?“, „Porovnej Prahu s Brnem“.
- Najde **obě** hesla v offline Wikipedii, i ve tvaru „Prahu / Brnem“ nebo jen podle příjmení.
- Z úvodu hesel vytáhne čísla: obyvatelé, rozloha, výška, délka, narození, vznik.
- Postaví z nich tabulku a **spočítá verdikt** („**Praha** je větší – 496 km² proti 230 km² (2,2×)“).
- Žádný jazykový model: čísla jsou přímo z encyklopedie, výpočet je deterministický, odpověď přijde za milisekundy.
- Když chybí heslo nebo údaj, mlčí a otázku převezme mozek.

**Nové databáze ke stažení (s RAG indexy):**

| Knihovna | Obsah | Použití |
|---|---|---|
| Wikizdroje – české texty | 43 tis. textů: zákony a Ústava, historické listiny, Erben, Němcová, Neruda, Mácha… | podklad pro mozek |

- Build pipeline (`build-library.yml`, `build-rag.yml`) teď umí libovolný dataset Wikimedia na Hugging Face.
- Výsledky nesou svůj zdroj („Wikizdroje: Babička“, „Wikipedia EN: …“).
- Odpovědím bez mozku a Strážci faktů věří **jen česká Wikipedie**, takže reflex nikdy neodpoví anglicky ani úryvkem básně.

**Volba cloudu a modelu:**
- V Moduly → Cloud vybereš **hlavní cloud**, který odpovídá vždy první. Ostatní připojené jsou záloha při limitu nebo výpadku.
- U každého poskytovatele si vybereš **konkrétní model** z nabídky, kterou server opravdu má (načte se při „Ověřit“).
- „Automaticky“ znamená nejsilnější dostupný model.
- Ručně zvolený model, který server přestane nabízet, se sám vrátí na automatický výběr.

**Nové paušály s výkonnými modely (DeepSeek, Kimi K2, GLM, Qwen, gpt-oss 120B):**

| Služba | Cena (orientačně, ověř na webu) | Poznámka |
|---|---|---|
| **NanoGPT** (doporučeno) | ~12 $/měsíc | open-source modely v předplatném, jeden klíč, OpenAI API |
| Ollama Cloud | Pro ~20 $/měsíc | velké modely na serverech Ollama |
| Synthetic | od ~20 $/měsíc | limit zpráv na 5 h, data neukládá |

**Δ Strážce faktů v2:**
- **Primárně pro režim bez mozku:**
  - odpověď z paměti odpovědí nebo tabulek, kterou offline Wikipedie vyvrací, se nepoužije a smaže; odpoví Wikipedie,
  - odpověď mozku, kterou Strážce vyvrátí, se do paměti odpovědí **vůbec neuloží**,
  - rychlé síto (String.contains, bez regexu) přeskočí výpočty, čas a převody za desítky ns,
  - paměť výsledků zajistí, že se stejná odpověď hledá v knihovně jen jednou.
- Kontroluje i **celé datum** (stejný rok, jiný den → upozorní).
- Kontroluje závorky typu „(* 1950)“ a „(14. 7. 1939 – 1. 10. 2019)“.
- Kontroluje **rok založení** („Univerzita Karlova byla založena 1350“ → Wikipedie: 1348).
- Méně planých poplachů:
  - u jmenovců mlčí,
  - rok z vedlejší věty („narodil se v Plzni, kde v roce 1945…“) ignoruje,
  - nejistá data („asi 1045“) nekontroluje,
  - věří jen české Wikipedii.

**UI od nuly (FRESH):** ploché, svěží, minimalistické, stále jen černá a titan.
- Odstraněno:
  - přechody barev,
  - stíny,
  - průhledné hrany,
  - skenovací animace při psaní,
  - animace příchodu zpráv,
  - posun a filtr u oznámení,
  - neviditelné SVG gradienty (−20 uzlů DOM).
- Animuje se jen transform a opacity.
- Aktivní záložka = titanová kapsle.
- Tabulky mají čitelný první sloupec.

## AVIOR Delta II

**Δ Strážce faktů (jedinečná funkce):** každou odpověď mozku nebo cloudu AVIOR ověří **přímo v telefonu proti
offline Wikipedii**, bez internetu a bez dalšího modelu, za pár milisekund. Když se odpověď liší od encyklopedie,
pod odpovědí se objeví řádek:

> Δ Wikipedie uvádí jinak: **Karel Gott** – narození **14. července 1939** (v odpovědi: narození 1938).

- Ověřuje úzce, ale přesně:
  - data narození a úmrtí osob („X se narodil … 1938“),
  - roky v závorce za jménem („X (1939–2018)“).
- Porovnává s úvodní závorkou hesla.
- Při jakékoli nejistotě (heslo nenalezeno, závorka není životopisná, nejednoznačné jméno) **mlčí**.
- Rozdíl je vidět i v reportu pro Clauda.

**Řada Delta v UI:** obrysová řecká **Δ** v logu (hlavička, úvod, ikona) a u názvu AVIOR.

**Jen tykání:** převod odpovědí na vykání je pryč (zbytečná práce). Formální obraty („Můžete mi říct…“) AVIOR dál
rozpozná, aby otázce porozuměl. Odpovídá vždy tykáním.

**Zátěžové testy (`StressTest`):**

| Režim | Výsledek |
|---|---|
| Bez mozku | 24 000 otázek z 8 vláken za ~2 s (**~12 000/s**), medián 0,14 ms, **0 chyb** při souběhu, paměť neroste |
| S mozkem offline (simulovaný mozek, skutečný llama.cpp v CI kontejneru neběží) | režie kolem mozku (znalosti, paměť, RANDY, zadání) **< 10 ms**, zrušení uprostřed odpovědi **81 ms** |
| Cloud | 300 dotazů / 6 vláken proti serverům s náhodnými 429 a 503: **odpověď dostal každý**, 126× přepnutí na zálohu |

**Úklid:** odstraněno 6 nepoužívaných funkcí (busyFor, forgetAnswer, answerCount, clearVectors, setMessageMeta,
zipEntries) a kód vykání.

## AVIOR Delta I

Od této verze se AVIOR značí **Delta** + římská číslice (Delta I, Delta II…). Uvnitř se verze porovnávají podle
čísla sestavení (`versionCode` 1000, 1001…). Vydání na GitHubu má tag `avior-delta-i-c1000` a soubor
`AVIOR-Delta-I.apk`. Kontrola aktualizací správně pozná, že Delta I je novější než 8.9.0.

**Test „jako člověk, který s AI skoro nepracoval“** (`NoviceUserTest`, 77 otázek: celé věty, vykání, pozdrav +
otázka + poděkování, „Nevíte náhodou…“, „Můžete mi spočítat…“): bez mozku **36 → 76** (zbývá počasí, potřebuje
internet). Co se doplnilo:
- **Víc vět ve zprávě** („Ahoj. Kolik je hodin? Děkuji moc!“): věty, které jsou jen pozdrav nebo poděkování,
  se zahodí a zbude otázka.
- **Formální obaly:** „Můžete mi prosím říct“, „Mohl byste“, „Řekněte mi“, „Nevíte náhodou“, „chtěla bych se zeptat“,
  „Potřebovala bych vědět“, „Promiňte“, „Prosím vás pěkně“, „Dobrý den Avioře“, „mohla bych poprosit o“.
- **Jiné formulace:** „Kdo je u nás / teď prezident“, „Jak se jmenuje náš prezident“, „Kdo vládne“, „Kdo jste“,
  „Co umíte“, „Děkuji za pomoc“, „Hezký den“, „jaké je datum“, „jak dlouho ještě do Silvestra“, „další státní svátek“,
  „kolik dní má tento měsíc“.
- **Míry slovy:** „jedna míle“, „půl kila“, „čtvrt kila“, „kolik deka je půl kila“, „v kilech“, „kolik stupňů
  Fahrenheita je 25 stupňů Celsia“, „kolik je to v eurech 1000 korun“.
- **Nouzová čísla podle situace:** „když hoří“ → **150**, „potřebuji sanitku“ → **155**, policie → **158** (vždy i 112).

**Opravy a zjednodušení:**
- „Kdy jsou LETOS Velikonoce“ (už byly) → řekne, že letošní byly, a dá příští.
- Peníze na 2 desetinná místa („41,15 EUR“), „1 míle“ místo „1 mil“, věk oslovuje přímo („Je ti 71 let“).
- Věk z ročníku i v delší větě („Narodil jsem se v roce 1960, kolik je mi let?“).
- **Méně kódu, méně práce:** definice z Wikipedie řešily dva moduly a každý prohledal 534 tisíc článků. Zůstal jen
  WikiReflex. Odstraněn nepoužitý parametr a vzor, kompilátor je bez varování.

## Novinky 9.0 – E-ARL opraven, offline Wikipedie bez mozku, start na úvodu, mazání cache

**Kritická chyba (od 8.4): offline Wikipedie bez mozku na telefonu nefungovala vůbec.**
- Knihovna vrací názvy s předponou („Wikipedie: Fotosyntéza“). E-ARL i reflex definic porovnávaly holý název
  s celým řetězcem, takže přesná shoda nenastala nikdy a cesta „výňatek z knihovny“ se na reálných datech
  nespouštěla. Testy to nechytily, protože používaly názvy bez předpony.
- Teď je jedna sdílená funkce `WikiReflex.titleMatches` (bez předpony, s rozlišovacím názvem „Brno (město)“).
  Nový test pracuje se skutečným tvarem dat.

**WikiReflex – model bez mozku čte a chápe offline Wikipedii** (přes Pavučinu sdílený zdroj znalostí):
- „Kdy se narodil / zemřel X“: datum (a místo) z úvodní závorky hesla.
- „Kde leží X“, „kdo založil / kdy vznikl X“: věta s polohou nebo vznikem.
- „Co je / kdo byl X“: první věty hesla.
- Odpovídá jen při přesné shodě hesla. Jinak mlčí a rozhodne mozek. Uvádí zdroj.
- Opraven výběr vět: tečka za číslem v datu („1. října 2019“) končila větu, takže se definice osob usekly
  uprostřed data narození.

**E-ARL:**
- Úvodní obaly („Dobře, jiná otázka – kdo…“) už nerozbijí rozpoznání typu otázky.
- **Nová kategorie „aktuální“:** ceny („kolik stojí nový iPhone“), počasí, výsledky a zprávy jdou vždy plnou
  cestou s webem. Dřív šly lehkou cestou a lehký mozek by jen hádal.
- **Kategorie „kód“** i pro žádost o kód („napiš funkci v Pythonu“), ne jen pro vložený kód.
- Nový test `EarlDecisionTableTest` hlídá rozhodnutí na skutečných otázkách z reportů.

**Start na úvodní obrazovce:** aplikace se otevře na úvodu, ne v posledním vlákně. Jen když AVIOR zrovna
odpovídá, ukáže rozpracované vlákno.

**Mazání cache** (Nastavení → Cache):
- **Vymazat cache AVIORu:** dočasné soubory, code cache, cache WebView a paměť Pavučiny. Mozky, Wikipedie,
  konverzace a paměť zůstanou. Ukáže uvolněné MB.
- **Vymazat cache telefonu:**
  - S rootem (po potvrzení s ukázkou příkazu) `pm trim-caches` vymaže cache všech aplikací, data zůstanou.
  - Bez rootu Android mazání cizích cache nedovolí, proto se otevře systémové „Uvolnit místo“.

## Novinky 8.9 – méně mozku, čistší odpověď, celé články

**Bez mozku zvládne víc** (sada 130 běžných otázek: **58 → 118**; zbytek je počasí a kurz kryptoměn, které potřebují
internet, povely telefonu, které řeší vlastní modul, a „kolik mi je“ bez data narození):
- **Svátky a změna času:** Štědrý den, Silvestr, Nový rok, Den matek (2. neděle v květnu), Den otců,
  Valentýn, Mikuláš, „kdy se mění čas“ (s tím, kterým směrem), „kolik týdnů do Vánoc“.
- **Jednotky:** akr, ar, hektar ↔ m², dekagram, kWh ↔ J, čas (s, min, h, dny, týdny), „hodina a půl“, „kolik má litr
  mililitrů“. Převody přijímají jednotky s číslicí (m2, km2).
- **Kurzy a čísla:** křížový kurz („kurz eura k dolaru“), „kurz eura dnes“, zlomky s lomítkem („1/4 z 100“).
- **Data:**
  - Slovníček 40+ pojmů (AI, inflace, bitcoin, blockchain, cloud, gravitace, fotosyntéza, HDP, DNA, demokracie,
    globální oteplování…).
  - Osobnosti (s „stav k“ u funkcí), počty obyvatel 15 zemí a Evropy, Gerlachovský štít.
  - Kalorie běžných potravin, linky pomoci (116 123, 116 111).
  - Chatové zkratky (OK, LOL, ASAP, BTW, FYI…).
  - Slovníček 80 slov česky → anglicky / německy.
- **Drobná konverzace:** „jsi tam?“, „haló“, „nuda“, „řekni mi něco zajímavého“, „vyber číslo“, „nashledanou“.
- **E-ARL:** holou definici („co je fotosyntéza“) s přesným heslem v offline Wikipedii zodpoví knihovna i tehdy,
  když je dostupný cloud. Rozvedení mozkem je na tlačítku.

**Čistší odpověď:**
- Logo s časem jen v technickém režimu.
- Zdroje sbalené do jednoho „N zdrojů ›“.
- Místo štítku „bez mozku“ jen tlačítko **↻ Rozvést** u poslední odpovědi.
- „Přečíst nahlas“ přesunuté do nabídky ⋯.
- Technický důvod odpovědi z knihovny je v technických údajích, ne v textu.

**Celé články a úvaha nad nimi:**
- `fetch_url` čte **celý článek po částech** (část 1/3, 2/3…). Text se stáhne jednou a sdílí 10 minut.
- `web_search` připomíná, že vrací jen titulky.
- Pravidla agenta: otevřít 1–3 články, přečíst je celé, odpovědět z obsahu, oddělit doložená fakta (u každého,
  z jakého článku) od vlastní úvahy (souvislosti, příčiny, co z toho plyne, co je nejisté).

**Tvrdý scan – opraveno:**
- Ořez kontextu v cloudu zdarma mohl agentovi zahodit samotný cíl úkolu. Teď první zpráva zůstává.
- Nové vzory zúžené, aby nechytaly cizí otázky („vánoční prázdniny“ ≠ Štědrý den, „dnes“ ≠ jednotka dní).
- Desetinná čísla z normalizátoru (Everyday mění čárky na mezery).

**Testy:**
- Nový `EverydayCoverageTest` (min. 117/130). Kontrolní sada otázek pro mozek rozšířená.
- Správnost 94/94, slang 102/102, přirozené formulace 150/150.
- Kliknutí na 120 tlačítek bez chyby JS.

## Novinky 8.8 – opravy podle reportů z reálného používání

- **Malý mozek zopakoval minulou odpověď:** „Prezident České republiky“ po otázce na Bílou horu dostal znovu
  Bílou horu.
  - Nová otázka na jiné téma teď jde mozku v telefonu **bez staré výměny**. Navazující otázky („a kdo ji vyhrál?“,
    „proč?“) a cloud historii dostávají dál.
  - **Pojistka proti ozvěně:** když mozek i tak vrátí skoro stejnou odpověď, AVIOR se zeptá znovu bez historie.
  - Kratší zadání přineslo i rychlejší čtení (v reportu 33 s ze 38 s padlo na čtení 1 435 tokenů).
- **Holé fráze bez otázky:** „Prezident České republiky“, „premiér“, „hlavní město Polska“ odpoví reflex (0,04 s),
  ne mozek.
- **Oprava od uživatele** („Špatně, hraje to Petr Rychlý“, „Ne, …“, „Mýlíš se…“): špatná odpověď se zapomene
  i v paměti odpovědí. Oprava se uloží jako fakt do paměti, takže příště ji uvidí i mozek.
- **Čtení celých článků:** agent dřív z novinové stránky dostal menu a titulky jiných článků. Nový režim čtení
  (`Web.mainContent`) bere text článku v tomto pořadí: strukturovaná data (articleBody), `<article>`, `<main>`,
  odstavce. Agent v cloudu vidí až 10 000 znaků článku.
- **Úklid odpovědí:** citační značky modelu gpt-oss (`【4】`, `【zdroj…】`) a zbytky značky sebereflexe
  (`⟨/SEBEREFLEXE⟩` v poučení) zmizí.
- **OpenRouter zdarma:** když v nabídce není nic z preferencí, vybere nejsilnější obecný model podle velikosti
  (dřív první v abecedě, např. malý model na kód).
- **Chutes:** text v aplikaci odpovídá aktuální nabídce (kredit, případně paušál).

## Novinky 8.7 – chyby z běžného používání

Hledání chyb očima běžného uživatele: správnost odpovědí (ne jen „něco odpověděl“) a typické toky v aplikaci.

**Nové odpovědi bez mozku (modul `Practical`):**
- **Světový čas:** „kolik je hodin v New Yorku / Tokiu / Londýně…“ – 50 měst a zemí, letní čas přes java.time.
  Odpověď uvádí i rozdíl proti Česku a jiný den, pokud tam už je.
- **DPH:** „21 % DPH z 1000“, „1000 s DPH“, „1000 bez DPH“ (výchozí 21 %, lze zadat 12 %).
- **Procenta:** „100 + 20 %“, „2500 − 15 %“, „sleva 30 % z 1200“, „o kolik % je 120 víc než 100“,
  „kolik procent je 30 z 120“.
- **Kalendář:** „kolikátý je dnes den v roce“, „kdy je příští pondělí“, „kolik dní je mezi 1. 1. a 1. 3.“,
  věk z celého data narození (s počtem dní do narozenin).
- **Dělení nulou:** věcná odpověď místo předání mozku.

**Opravy:**
- **Dvojité klepnutí na odeslat** dotaz hned zrušilo, protože druhé klepnutí trefilo „Zastavit“. Teď je na
  0,8 s ochrana.
- **Zpět ve vlákně** zavřelo aplikaci. Teď vrátí na úvod a aplikace se zavře až z úvodu.
- **✗ u odpovědi z paměti odpovědí** smazal jen přesné znění otázky. Špatná odpověď uložená pod jinou formulací
  přežila a vracela se. Teď se smažou všechny záznamy, které by na otázku pasovaly.
- **Čísla s mezerami v tisících** („333 333,33“, „1 215 Kč“) a rozumný počet desetinných míst.
- **Jednotky v převodech** se píšou jak je uživatel napsal („1,5 l = 1500 ml“, „100 Nm“), dřív „L“, „ML“, „NM“.
  Velikost písmen nese význam (Nm × nm), proto se nemění. Měny jsou dál velkými.
- **Pozdrav stejným pozdravem:** „Dobré ráno“ → „Dobré ráno!“, „Dobrý večer“ → „Dobrý večer!“.
- „kolik je to korun 50 eur“ (obrácený slovosled) → převod.
- Modul Praktické běží před obecnými procenty (dřív „sleva 30 % z 1200“ vrátilo 360 místo 840).

**Testy:**
- `CorrectnessTest`: 94 běžných otázek s očekávanou odpovědí při pevném datu, **94/94**.
- V náhledu: skript klikl na 121 tlačítek na všech obrazovkách a v jejich listech a nenašel žádnou chybu JS.
- V náhledu: dlouhý obsah (URL, tabulka, kód) nepřetéká šířku obrazovky.

## Novinky 8.6 – nezávislý průchod celou aplikací

Průchod částmi, které dřív neprošly podrobnou revizí (mozek v telefonu, databáze, agent, streamování v UI):
- **Přírůstkové vykreslování odpovědi:** hotové odstavce se převedou z markdownu jednou a připojí se. V každém snímku
  se převádí jen rozepsaný konec. Dřív se celý text převáděl při každém snímku, takže u dlouhých odpovědí
  z rychlého cloudu práce rostla kvadraticky. U odpovědi s 10 800 znaky (CPU 4× zpomaleno) je nejhorší snímek
  **15,4 → 9,8 ms** a rozdíl s délkou roste. Výsledek je ověřeně shodný s celým převodem, včetně bloků kódu.
- **Bezpečné zrušení:** zrušení dotazu už nevolá nativní `cancel` nad mozkem, který byl mezitím uvolněn z paměti.
- **Bez nálezu:**
  - Databáze: WAL, indexy na vláknech a zprávách, transakce, FTS4.
  - Agent: hlídač opakovaných volání, firewall proti skrytým pokynům, limit kroků a času.
  - Mozek: zámek proti souběhu, úsporné načtení, znovupoužití KV cache.

## Novinky 8.5.1 – drobnosti, které uživatel pozná

- **„Pokračovat ›“ u useknuté odpovědi:** když odpověď narazí na limit délky (telefon, cloud i Claude), poslední
  zpráva nabídne pokračování přesně tam, kde skončila. Dřív končila v půlce bez možnosti navázat.
- **Mozek se na pozadí uvolní:** při vypnutém „Mozek připraven“ AVIOR uvolní mozek z RAM (3–5 GB zpět systému)
  po 5 minutách v pozadí. Telefon je pak svižnější v jiných aplikacích. Po návratu se mozek znovu přednačte.
- **Rozepsaná zpráva přežije zavření aplikace systémem.** Ukládá se po chvilce klidu, ne při každém písmenu.
- **Úvod nenačítá katalog mozků při každém vykreslení.** Drží ho v mezipaměti a obnoví ho až po změně mozků.

## Novinky 8.5 – doporučení zapracována, poslední kolo oprav

- **Paměť odpovědí pozná přeformulovanou otázku:** „Jaké je hlavní město Austrálie?“ (dřív ověřeno ✓) a „hele,
  jaký je hlavák austrálie“ vrátí uloženou odpověď bez mozku.
  - Bez modelu pro význam (ten by stál RAM): porovnávají se kanonické tvary z normalizátoru.
  - Jen u záznamů se společným klíčovým slovem, kanonický tvar se pamatuje.
- **E-ARL v podrobnostech odpovědi** (technický režim) a v reportu: cesta, kategorie, jistota, důvody.
- **Opravy:**
  - Výňatek z knihovny místo mozku jen v režimu **Auto**. Výslovná volba „V telefonu“, „Cloud“ nebo „Turbo“ má
    přednost.
  - U volby „V telefonu“ E-ARL nepočítá s cloudem.
  - Lehká cesta E-ARL nespouští řetězec podmodelů RANDY.
- **RAG index české Wikipedie hotový:** 533 800 článků, vektory 256 rozměrů int8, ke stažení 127 MB.
- **Úmyslně odloženo, protože by zatížilo:**
  - Rozpoznávání podle významu v E-ARL (embedder trvale v RAM).
  - Minifikace kódu R8 (riziko pádu, nutný test na telefonu).

## Novinky 8.4.1 – zásada: každá úprava musí šetřit

Nové moduly (normalizátor, oprava překlepů, E-ARL) prošly měřením nejhoršího případu, tedy otázky, která
stejně jde mozku:
- **Zkompilované vzory místo kompilace při každém volání:** 209 regulárních výrazů v reflexech se dřív kompilovalo
  při každém volání. Teď se zkompilují jednou za běh (`rx()` s omezenou pamětí).
- **Normalizátor jen na krátké otázky** (do 14 slov). Dlouhá zadání pro mozek se vůbec nepřepisují.
- **Oprava překlepů** jen do 9 slov. Slovník se prohledává jen ve skupině podobné délky a hotové opravy se
  pamatují.
- **Měření:**

| | Před | Po |
|---|---|---|
| Režie u otázky pro mozek (reflex + varianty + E-ARL) | 1,82 ms | **0,54 ms** |
| Odpověď bez mozku (průměr) | 0,47 ms | **0,22 ms** |
| Everyday | 0,47 ms | 0,035 ms |
| Normalizátor | 0,80 ms | 0,05 ms |

- **Hlídač výkonu:** test `OverheadTest` spadne, pokud režie překročí 2 ms. Zpomalení tak nemůže projít bez
  povšimnutí.

## Novinky 8.4 – E-ARL, Neurální pavučina, hloubkový scan

**E-ARL (Early Answer Routing Layer)** – modul před RANDYm, který u každé otázky za zlomek milisekundy rozhodne,
kolik myšlení je potřeba:

| Cesta | Kdy | Co se stane |
|---|---|---|
| REFLEX | odpověď zná deterministický reflex | 0 ms mozku |
| RETRIEVE | definice („co je fotosyntéza“) s přesným heslem v offline knihovně, mozek není v RAM ani cloud | výňatek se zdrojem, 3–5 GB mozek se vůbec nenačte |
| LIGHT | krátká jasná otázka (fakt, definice, konverzace) | 1 průchod, bez přemýšlení, v cloudu rychlý model |
| BRAIN | běžná otázka | RANDY volí hloubku |
| DEEP | složené otázky, strategie (RANDY L3+), dlouhá analýza či tvorba, kód | RANDY naplno |

- **Učí se z hodnocení:**
  - ✗ u výňatku zpřísní práh dané kategorie o 5 bodů, ✓ ho uvolní o 1.
  - Opakované ✗ u lehké cesty pošle kategorii rovnou plnému mozku.
  - Prahy jsou uložené v telefonu.
- **Ruční volba má přednost:** vlastní volba uživatele (varianta, role, úroveň, `/příkaz`, konkrétní mozek) E-ARL nikdy
  nepřebije.
- **Rozhodnutí je vidět** v reportu pro Clauda: cesta, kategorie, jistota a důvody.

**Neurální pavučina** – sdílené signály jednoho dotazu napříč celým AVIORem. Reflexy, E-ARL, RANDY, znalosti
i offline odpověď se ptají na totéž: text bez diakritiky, kanonické varianty, úroveň RANDY, časovou citlivost
a výsledky hledání. Každý signál se teď spočítá **nejvýš jednou a líně** a sdílí se:
- Uzel dotazu žije v paměti posledních 48 dotazů a nic neběží na pozadí.
- Výsledky hledání ve znalostech se sdílí 90 s. Při změně znalostí (import, balíček, smazání) se automaticky
  zapomenou.

**Hloubkový scan – opraveno:**
- Sekvenční Konzilium: jeden selhaný člen (limit, výpadek) shodil celou odpověď. Teď se přeskočí a rozhodne
  zbytek.
- E-ARL by bral „napiš mi…“ jako obecnou otázku, protože normalizátor odstraní obal. Tvorba a analýza se teď
  poznávají z původního znění.

## Novinky 8.3 – rozumí slangu, nářečí, slovenštině i angličtině; odpoví v každém režimu

**Bez mozku – rozpoznávání otázek (`QuestionNorm`, vrstvy od nejmenší změny):**
- **Angličtina** (krátké dotazy): „what time is it“, „capital of France“, „15 percent of 200“, „7 times 8“,
  „flip a coin“, „tell me a joke“…
- **Výplňová slova** pryč: vole, kámo, brácho, jo, ne, šak, vé, esi víš, zrovna, přesně, pls, plz, thx…
- **Slang a obecná čeština:** hoďek, sedum, vosum, kilák, hlavák, prezoš, stovka, dyť, zejtra…
- **Nářečí:** kolék, keré/kerý, gde, esi.
- **Slovenština:** koľko, aký, kto, kedy, ako sa máš, meniny, Vianoce, ďakujem, percent, vojna, francúzska…
- **Chat:** „kolik je h/hod“, „2x3“, „sqrt 81“, „dk“, „thx“, VELKÁ PÍSMENA, „???“.
- **Oprava překlepů** (Damerau–Levenshtein: 1 chyba do 7 písmen, 2 od 8) proti slovníku klíčových slov
  a všech států a hlavních měst: hodim → hodin, kolok → kolik, francue → francie, vanco → vánoc,
  napsla → napsal…
- **Číslovky:** dvěstě až devětset.
- **Opravené chyby:**
  - převodník četl „mililitrech“ jako míle
  - samotné „díky“ se ořízlo do prázdna
  - „no a kolik je hodin“ se bralo jako složená otázka

**Odolnost napříč režimy (AI odpoví vždy):**
- **Výpadek cloudu:** cloud selže (limit, výpadek, síť) a v telefonu je mozek → odpověď převezme telefon. Uživatel
  dostane odpověď a v „Proč takto“ uvidí důvod.
- **Bez mozku a bez cloudu:** offline bez staženého mozku → **odpověď z offline knihovny** (Wikipedie,
  dokumenty, balíčky). Je to extraktivní výňatek nejrelevantnějších vět se zdrojem, nic si nevymýšlí, a když zdroj
  neodpovídá aspoň z poloviny, raději mlčí.
- **I když mozek selže**, AI nejdřív zkusí offline knihovnu a teprve potom ukáže chybu.

**Měření:**
- Slang, nářečí, slovenština, angličtina a překlepy: **102/102** (předtím 29/102).
- Přirozené formulace 150/150.
- 50 otázek, které patří mozku: 0 falešných odpovědí.
- Rozhodnutí trvá 0,48 ms na otázku.

## Novinky 8.2 – offline modul bez mozku rozumí otázkám jako člověk

- **Normalizátor otázek (`QuestionNorm`)** převede otázku tak, jak ji člověk napíše, na tvar, kterému rozumí
  reflexy. Původní znění se zkouší vždy první, takže nic, co fungovalo, se nerozbije. Pozná:
  - zdvořilé obaly: „prosím tě“, „řekni mi“, „můžeš mi říct“, „víš“, „zajímalo by mě“, „AVIOR,“…
  - hovorovou češtinu a překlepy: dneska, jakej, kolikátýho, hoďin, vteřin, kila, metropole, hl. město
  - čísla slovy: „sedm krát osm“, „dvacet procent z tisíce“, „pět set“
  - zlomky: „třetina z 90“, „polovina z 250“
  - mocniny: „2 na desátou“
  - převody v obráceném slovosledu: „kolik metrů je 5 km“, „kolik gramů má kilo“, „20 °C na fahrenheity“
  - kurzy: „za kolik je euro“, „kolik stojí dolar“
  - kalendář: „za jak dlouho jsou Vánoce“, „kdy slaví svátek Jana“, „jaký den bude 24. 12.“
  - politiku a stát: „kdo je prezidentem USA“, „kolik má NATO členů“, „kdy jsme vstoupili do EU“
- **Nová znalostní tabulka** (`facts/obecne.tsv`, 89 vzorů):
  - autoři knih a hudby, obrazy, vynálezy a objevy (včetně českých: Wichterle, Diviš, Ressel, Heyrovský)
  - vesmír (vzdálenost Měsíce a Slunce, π, e), chemické vzorce, lidské tělo
  - obyvatelstvo a rozloha Česka, zakladatelé firem
- **Dějiny:** přibyly obě světové války, pád Berlínské zdi, přistání na Měsíci, 11. září a další milníky.
- **Pojistka proti polovičním odpovědím:** složená otázka („kolik obyvatel má Praha *a jak se to změnilo*…“)
  jde vždy mozku.
- **Měření:**
  - Sada 150 otázek v přirozených formulacích: rozpoznáno **150/150** (předtím 87/150).
  - Nová sada 28 otázek, které musí jít mozku: 0 falešných odpovědí.
  - Rozhodnutí trvá 0,37 ms na otázku.

## Novinky 8.1 – tvrdý audit kódu

**Chyby (opraveno):**
- **Cloud padal na limitech:** orchestrátor posílal cloudu `max_tokens` 32 000. DeepSeek přijímá nejvýš 8 192 (jinak
  HTTP 400) a bezplatné tarify Groq a Cerebras požadavek odmítly jako příliš velký. Teď má každý poskytovatel
  vlastní strop výstupu a okno kontextu. Nejstarší historie se ořízne, systémová zpráva a poslední dotaz zůstanou.
  Hugging Face má strop 8 192.
- **Samooprava modelu:** když server model nezná (HTTP 404, free tarify je často mění), AVIOR stáhne aktuální
  nabídku, vybere platný model, zapamatuje si ho a dotaz zopakuje.
- **Soukromí:** pečeť „v telefonu“ se počítala ze seznamu zakázaných mozků, ve kterém chyběl nový cloud. Odpověď
  z cloudu tak mohla být označena jako soukromá. Teď se pečeť počítá ze seznamu povolených cest.
- **Povely pro telefon (root):** potvrzovaný příkaz drží Kotlin pod jednorázovým tokenem a UI posílá jen token a
  ano/ne. Kompromitované UI by tak nemohlo spustit jiný root příkaz, než jaký uživatel napsal. Příkaz shellu je
  vidět přímo v potvrzení.
- **LoRA:** mazání a volba adaptéru přijímají jen jméno souboru ve složce adaptérů (ochrana proti `../`).
- **Cloud bez internetu:** ručně zvolený cloud bez sítě teď odpoví mozkem v telefonu místo chyby spojení.
- **Konzilium:** rozhoduje nejsilnější dostupný mozek (Claude → cloud → Kimi…), ne telefon.
- **Neočekávaná chyba dotazu** nese číslo vlákna, takže se UI správně obnoví.
- **JNI:** popis modelu z GGUF jde do Javy jen jako tisknutelné ASCII, aby vlastní model se speciálními znaky
  nemohl shodit aplikaci.

**Výkon:**
- `state()`, který UI volá každou sekundu během odpovědi, už nedešifruje API klíče přes Android Keystore.
- Streamování dlouhých odpovědí posílá snímky řidčeji podle délky textu, takže je méně dat přes most do WebView.
- Okno, stavový řádek a navigace jsou čistě černé (OLED).

## Novinky 8.0 – TITAN: nový design od nuly, levné cloudy

- **Design TITAN (napsaný od nuly):** jen černá a titan, nikde nic barevného.
  - Stav se vyjadřuje tvarem a jasem: plná tečka = připraveno, pulz = pracuje, prázdný kroužek = nedostupné,
    čárkovaný okraj = chyba nebo odhad.
  - Popisky ve stylu HUD (mono, verzálky, titanová čárka), velký tenký titulek a kovový přechod jen na několika
    malých prvcích (odeslat, přepínač, průběh).
  - Při psaní odpovědi běží nad textem tenká skenovací linka.
  - Emoji z odpovědí modelu se vykreslují šedě a barevné symboly v textech aplikace jsou nahrazené textovými.
- **Levné placené cloudy** ve stejném modulu (Moduly → Cloud), pořád bez zátěže RAM:
  - **DeepSeek** – platíš jen spotřebu, kredit od 2 $. Při běžném chatování obvykle stojí jednotky dolarů
    měsíčně. Servery jsou v Číně; tajemství maskuje DLP štít.
  - **Chutes** – kredit (platí se spotřeba), případně paušál podle aktuální nabídky na chutes.ai/pricing
    (paušály se během roku 2026 měnily a ne každý účet je vidí).
  - Volba **Priorita: Rychlost / Kvalita**. Rychlost = nejdřív Cerebras/Groq zdarma, Kvalita = nejdřív placené
    silné modely a zdarma jako záloha.
- **Doporučení, jak cloud nastavit:**
  - Zdarma: Cerebras + Groq (stačí jeden, oba = záloha).
  - Nejlepší poměr cena/výkon: přidej DeepSeek (dobij 5 $). Pro výběr z mnoha modelů OpenRouter s kreditem 5 $.
  - Groq lze přepnout na tarif Developer: platí se spotřeba (gpt-oss-120b zhruba 0,15 $ / 0,60 $ za milion
    tokenů) a limity jsou vyšší.

### Jak připojit cloud (2 minuty)
1. Otevři odkaz „Získat klíč“ u poskytovatele v Moduly → Cloud (Cerebras: cloud.cerebras.ai, Groq:
   console.groq.com/keys, DeepSeek: platform.deepseek.com/api_keys, Chutes: chutes.ai/app/api).
2. Přihlas se (Google účet stačí) a vytvoř API klíč. U DeepSeeku dobij kredit, u Chutes zvol tarif.
3. Vlož klíč do AVIORu a klepni na Uložit. AVIOR spojení sám ověří a vybere nejsilnější dostupný model.
4. Hotovo: s internetem odpovídá cloud, offline telefon.

## Novinky 7.3 – Cloud zdarma, report pro Clauda, čistší vzhled

- **Cloud zdarma (nový modul):** Cerebras, Groq a OpenRouter. Klíč je zdarma a bez platební karty, mluví se
  přes OpenAI Chat Completions. Odpovídá velký otevřený model (gpt-oss-120b, Kimi K2, Llama 3.3 70B, Qwen 3)
  rychlostí stovek až tisíců tokenů za sekundu. Telefon přitom do RAM nenačítá nic.
  - Model si AVIOR vybírá z aktuální nabídky serveru (`/models`) podle pořadí preferencí, takže ho nerozbije,
    když poskytovatel model vyřadí.
  - Když server vrátí limit (429), výpadek nebo neznámý model dřív, než začne psát, dotaz plynule přejde na
    dalšího poskytovatele.
  - V režimu **Auto** s internetem odpovídá Cloud zdarma (přepínač „S internetem odpovídá cloud zdarma“).
    Offline a v Air-gapu dál myslí telefon a mozek se do RAM nepřednačítá zbytečně.
  - **Turbo** používá nejdřív Cloud zdarma, mozek v telefonu se po uložení klíče uvolní z paměti.
  - DLP štít maskuje tajemství i pro tyto servery.
- **Report pro Clauda:** v chatu ⋯ → „Report pro Clauda“ vyjede textový rozbor vlákna. U každé odpovědi uvádí
  cestu (reflex / telefon / cloud a proč), model, čas, úroveň RANDY, jistotu, ukotvení, tokeny, regulátor,
  výkon telefonu, zdroje a hodnocení. K tomu přidá posledních 40 událostí (fáze, přepnutí serveru, chyby).
  Lze ho zkopírovat nebo sdílet a text zpráv jde vypnout. API klíče v něm nikdy nejsou.
- **Vzhled „jednoduše, ale chytře“:**
  - OLED černá (na Pixelu 9a černé pixely nesvítí, takže šetří baterii) a plochy bez rámečků.
  - Pole pro dotaz funguje jako ve Zprávách: když je prázdné, ukazuje mikrofon, s textem šipku k odeslání.
  - Akce u starších odpovědí se ukážou až po klepnutí, poslední odpověď je má vždy.
  - Volba výkonu je na úvodu jen mimo výchozí nastavení a tipy na rychlost zmizí, když už odpovídá cloud.
  - List ze spodu má úchyt.
- Na profilu Pixelu 9a (CPU 4×) se stránka načte za 271 ms (dříve 347 ms).

## Novinky 7.2 – klid v UI, nic zbytečného na očích

- **Jen to, co potřebuješ:** během odpovědi místo interních fází (RANDY, varianty, procenta jistoty) jen
  „Přemýšlím… / Píšu… / Pracuji na úkolu…“. U hotové odpovědi zůstává čas, zdroje a akce. Jistota, myšlenkový
  pochod, Podrobnosti a štítky modulů jsou schované.
- **Hlavička:** štítek vpravo nahoře se ukáže jen při stahování (↓ %) nebo když nejedeš v režimu Auto. Podtitul
  píše jen „offline“ nebo „stahuji … na pozadí“, jinak je prázdný.
- **Technické detaily na přání:** Nastavení → Vzhled → „Technické detaily“ vrátí všechno (fáze, jistotu, RANDY
  nápovědu, „co jsem se naučil“, myšlenky).
- **Méně vyrušování:** pryč jsou hlášky o milnících učení, průběžné „Čtu 3/40…“ při importu i mezikrok
  „Stavím sémantický index“. Tip na Turbo jde zavřít ✕.
- **Méně práce na pozadí:** hlavička se překresluje jen při změně (dřív každou sekundu i s logem). Časovač stojí,
  když je aplikace schovaná, a při stahování se obnovuje jen co 3 s. RANDY nápověda už nevolá jádro při každém
  stisku klávesy. Na profilu Pixelu 9a (CPU 4×): vlákno s 200 zprávami 47 → 31 ms, Moduly 32 → 19 ms,
  načtení 367 → 347 ms.

## Novinky 7.1 – domů, méně mozku, rychlost měřená jako na Pixelu 9a

- **Návrat na domovskou obrazovku:** klepnutí na logo AVIOR, druhé klepnutí na záložku Chat, nebo „⌂ Domů“
  v seznamu vláken. Když odejdeš domů během odpovědi, AVIOR tě do vlákna nevytrhne – jen dá vědět, že je hotovo.
- **Mozek se zapíná méně:** nový reflex `Everyday` – drobná konverzace (jak se máš, ok, co umíš, dobrou noc),
  počty slovy („7 krát 8“, „odmocnina z 144“, „2 na 10“, „15 procent z 200“, „sin 30“), převody v přirozené
  řeči („5 km v metrech“, „1 míle na km“, „100 eur na koruny“, „30 stupňů celsia ve fahrenheitech“), kurzy ČNB,
  zítra/včera, rok, měsíc, délky měsíců, věk a roky od události, vtip, mince, kostka, náhodné číslo.
  Pokrytí běžných jednoduchých otázek bez mozku stouplo z **30/79 na 75/79** (hlídá test `CoverageTest`).
- **Stejná otázka podruhé = okamžitě:** odpověď mozku v telefonu se uloží a u téměř shodné otázky se vrátí
  bez generování (ne u otázek, které se mění v čase).
- **Plynulost na výkonu Pixelu 9a** (`tools/ui-preview/perf.js`: viewport 1080×2424 @ 2,625, CPU zpomalené 4×):
  vlákno 200 zpráv 244 → **47 ms**, Nastavení 182 → **31 ms**, Moduly 86 → **32 ms**, načtení 527 → **367 ms**,
  nejhorší snímek streamu 3 ms. Převedený markdown se pamatuje, úvodní dávka 24 zpráv, karty mimo obrazovku
  se nevykreslují. Snímky náhledu jsou v rozměrech Pixelu 9a.

## Novinky 7.0 – nový design „simply clever“, další databáze, plynulost

**Nový design celého UI** – klidný, střídmý, nic okázalého na první pohled; chytré až v používání:
- grafitová paleta s jediným titanovým akcentem, hierarchie písmem a prostorem místo rámečků a efektů,
  jednobarevná značka; **žádné přechody, záře ani rozostření** (backdrop-filter) – výrazně lehčí pro WebView,
- úvod: pozdrav, jedna stavová řádka (mozek v paměti · výkon) a návrhy jako přehledný seznam,
- chat: odpověď bez bubliny na celou šířku, tabulky a kód čitelně, akce nenápadně pod odpovědí,
- dotykové cíle ≥ 44 px, tabulková čísla, respekt k systémovému „omezit animace“, světlý i tmavý motiv.

**Plynulost:** zprávy mimo obrazovku se nevykreslují (`content-visibility`), dlouhé vlákno zobrazí jen
posledních 60 zpráv (starší na klepnutí, bez skoku pozice), lehčí styly bez drahých efektů.
Reflexy a povely rozhodnou za ~0,24 ms na otázku.

**Další odpovědi bez mozku:**
- **měny a telefonní předvolby** 64 států (i obráceně „+44 je předvolba čeho?“),
- **zeměpisné a přírodní rekordy** ČR i světa (Sněžka, Vltava, Rožmberk, Everest, Nil, Vatikán…),
- **české dějiny** (Bílá hora, vznik ČSR, Mnichov, okupace, Heydrich, únor 1948, srpen 1968, Palach,
  Charta 77, sametová revoluce, Hus, Karel IV., Lipany, Slavkov…),
- **zkratky** (DPH, IČO, DIČ, OSVČ, ČNB, NÚKIB, GDPR, LLM, RAG, VPN, IBAN…).

## Novinky 6.9 – politika bez mozku

Nový reflex `core/agent/Politics.kt` (data `facts/strany.tsv`, `facts/pravice.tsv`):
- **Historie a stálá fakta (jistota 97 %):** „Kdo byl premiér v roce 2015?“, seznam premiérů, první premiér,
  vítězové sněmovních voleb 2013–2025, prezidentská volba 2023, NATO (vznik, článek 5, 32 členů),
  EU (vznik, 27 států), stálí členové RB OSN, začátek války na Ukrajině, útok Hamásu, sametová revoluce, vznik ČR.
- **Funkce s pevným mandátem (jistota 90 %, vždy „stav k 2026“):** premiér ČR, prezident USA,
  předsedkyně Evropské komise, generální tajemník NATO.
- **Strany (92 %):** „Co je ODS / TOP 09 / KDU / STAN / Piráti / ANO / SPD / Motoristé / SOCDEM / KSČM /
  Stačilo! / SPOLU?“ – zařazení, frakce v EP, role ve volbách.
- **Pravicový pohled bez mozku (90 %):** „Jaký je pravicový pohled na migraci / EU / daně / energetiku /
  Ukrajinu / USA / Izrael / Čínu / společnost?“ – vždy označený jako názorový směr a s hlavním protiargumentem.

## Novinky 6.8 – politika v offline databázi

Tři nové offline balíčky (instalují se samy po aktualizaci) a politická fakta v reflexech:
- **Česká politika · fakta** – ústavní systém, Parlament (200 poslanců, 81 senátorů, volební klauzule),
  prezidenti a všichni premiéři ČR, sněmovní volby 2025, přehled stran, NATO/EU/Schengen, klíčové události.
- **Zahraniční politika · fakta** – EU a její instituce, NATO (článek 5, cíl 5 % HDP), USA, Rusko a válka na
  Ukrajině, Blízký východ, Čína a Tchaj-wan, OSN.
- **Politika · pravicový pohled** – konzervativní a liberálně-pravicové postoje k daním a rozpočtu, EU, migraci,
  energetice a klimatu, bezpečnosti a Ukrajině, USA/Izraeli/Číně a společenským tématům; **výslovně označené
  jako pohled**, s rozlišením proevropské a euroskeptické pravice a s hlavním protiargumentem u každého tématu.
- **Reflexy bez mozku** (stálá fakta): prezidenti ČR, první prezident, počet poslanců a senátorů, data vstupu
  do NATO, EU a Schengenu. Aktuální funkce (premiér, vláda) a výsledky voleb jsou označené „stav k 2026 –
  ověř“ a odpovídá na ně mozek, aby AVIOR neodpovídal zastarale s falešnou jistotou.

## Novinky 6.7 – offline databáze faktů (odpovědi bez mozku)

Nový reflex `core/agent/Facts.kt` s kurátorovanými daty v `core/src/main/resources/facts/` – odpovídá
okamžitě, offline a bez mozku (jistota 97 %), rozumí českému skloňování:
- **Jmeniny** – celý občanský kalendář (366 dní): „Kdo má dnes/zítra svátek?“, „Kdo má svátek 24. 12.?“,
  „Kdy má svátek Jana?“, „Kdy slaví Petr?“ (i se dnem v týdnu a počtem dní do svátku).
- **Státní svátky ČR** včetně pohyblivých **Velikonoc** (výpočet pro libovolný rok): „Kdy jsou Velikonoce 2027?“,
  „Je dnes státní svátek?“, „Kdy je příští státní svátek?“, seznam svátků roku.
- **Hlavní města všech 195 států** a kontinent: „Jaké je hlavní město Německa?“, „Praha je hlavní město čeho?“.
- **Všech 118 chemických prvků**: „Jaká je značka zlata?“, „Co je Fe za prvek?“, „prvek číslo 92“.
- **Kraje ČR** a krajská města, **planety**, **fyzikální konstanty**, **předpony SI**, **tísňová čísla**.
- **Převodníky**: římské číslice (oběma směry), Morseova abeceda (oběma směry), hláskování NATO, přestupný rok.
- 7 testových sad včetně ochrany proti falešným shodám („Proč slaví lidé Vánoce?“ jde mozku).

## Novinky 6.6 – AVIOR jako rozšířené ovládání telefonu (i s rootem)

Povely v češtině – napsané nebo vyslovené (i přes asistenta / dlaždici) – se provedou hned, **bez mozku**:
- **Svítilna**, **hlasitost** (ztlum, zesil, „hlasitost 40 %“), **přehrávání** (pauza, přehraj, další, předchozí),
  **budík** („budík na 6:30“, „vzbuď mě v 6.30“), **minutka** („minutka 10 minut“), **jas** („jas 30 %“),
  **Nerušit**, **otevření nastavení** (Wi-Fi, Bluetooth, baterie, displej, úložiště, poloha, vývojář, NFC…),
  **spuštění aplikace podle názvu** („spusť Spotify“), **vytočení čísla** (hovor potvrdíš), **stav telefonu**.
- **Wi-Fi, Bluetooth, režim letadlo:** Android od verze 10/13 aplikacím přímé přepnutí zakazuje → AVIOR otevře
  systémový panel; **s rootem přepne přímo** (`svc wifi`, `svc bluetooth`, `cmd connectivity airplane-mode`).
- **Root** (Magisk / KernelSU), vždy s potvrzovacím dialogem: ukončení všech aplikací na pozadí (`am kill-all`),
  násilné ukončení aplikace podle názvu, uvolnění systémové mezipaměti, restart, vlastní příkaz `root: …`.
- **Bezpečnost:** povely se rozpoznávají jen z tvých vlastních slov – text z webu, souborů ani odpověď modelu
  akci nespustí (prompt injection nemá cestu). Otázka ≠ povel („Jak zapnout svítilnu na iPhonu?“ jde mozku).
  Jas a Nerušit potřebují tvé jednorázové povolení (tlačítka v kartě Ovládání telefonu). Testy.

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
