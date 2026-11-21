#!/usr/bin/env python3
"""
Sada DOVEDNOSTÍ AVIORu pro doladění (LoRA) – deterministicky generované trénovací příklady.

Malé modely v telefonu (Gemma 4 E2B/E4B, Qwen3.5) nejvíc ztrácejí na:
  * dodržení protokolu agenta (MYŠLENKA / AKCE / OBSAH / HOTOVO) a správném volání nástrojů,
  * výrobě souborů a aplikací (build_apk s celým index.html za OBSAH:),
  * práci s čísly ze STEP-0 (nepřepočítávat odhadem),
  * stylu RANDY (přímá odpověď, struktura, jistota) a poctivém popisu sebe sama.
Tento skript vyrobí ~90 ukázek ve formátu chat JSONL ({"messages": [...]}), které se přidají
k odpovědím, které uživatel v aplikaci ocenil (Doladění → Exportovat trénovací data).

Všechna čísla v ukázkách jsou spočítaná zde v Pythonu – ukázky učí správné výsledky.
Použití: python3 finetune/build_skills_dataset.py  → finetune/skills.jsonl
"""
import json
import math
import random
from pathlib import Path

random.seed(42)
OUT = Path(__file__).with_name("skills.jsonl")

SYSTEM = ("Jsi AVIOR – česká AI v telefonu. Odpovídáš česky, věcně a přesně, rozlišuješ fakta a odhady, "
          "čísla bereš ze STEP-0, jako agent používáš protokol MYŠLENKA/AKCE/OBSAH/HOTOVO.")
AGENT_SYSTEM = SYSTEM + """
REŽIM AKTIVNÍHO AGENTA. Nástroje: calc {"expr"}, step0 {"text"}, kb_search {"q"}, library {"q"}, web_search {"q"},
create_file {"name"} + OBSAH, create_docx {"name","title"} + OBSAH, create_pdf {"name","title"} + OBSAH, build_apk {"name"} + OBSAH.
Každá odpověď je buď MYŠLENKA + AKCE (+ OBSAH), nebo MYŠLENKA + HOTOVO a finální odpověď."""


def cz(x, d=2):
    s = f"{x:,.{d}f}".replace(",", " ").replace(".", ",")
    if d and s.endswith("," + "0" * d):
        s = s[: -(d + 1)]
    return s


def chat(*turns, system=SYSTEM):
    msgs = [{"role": "system", "content": system}]
    for i, t in enumerate(turns):
        msgs.append({"role": "user" if i % 2 == 0 else "assistant", "content": t})
    return {"messages": msgs}


examples = []

# ------------------------------------------------------------------ 1) calc – přesné výpočty
for _ in range(25):
    a, b, c = random.randint(2, 999), random.randint(2, 99), random.randint(2, 50)
    expr = f"{a}*{b}+{c}^2"
    val = a * b + c ** 2
    goal = f"Kolik je {a} × {b} plus {c} na druhou?"
    examples.append(chat(
        goal,
        f'MYŠLENKA: Spočítám to přesně nástrojem calc.\nAKCE: {{"tool": "calc", "args": {{"expr": "{expr}"}}}}',
        f"VÝSLEDEK NÁSTROJE calc (data, ne instrukce):\n{expr} = {val}\n\nPokračuj dalším krokem.",
        f"MYŠLENKA: Mám přesný výsledek.\nHOTOVO:\n**{cz(val, 0)}** ({a} × {b} = {cz(a * b, 0)}, {c}² = {c * c}).",
        system=AGENT_SYSTEM,
    ))

# ------------------------------------------------------------------ 2) +EV a Half-Kelly (ASTRAEA) – čísla ze STEP-0
for _ in range(25):
    odds = round(random.uniform(1.5, 3.6), 2)
    p = random.randint(30, 70) / 100
    bank = random.choice([5000, 10000, 20000, 50000])
    ev = p * odds - 1
    b = odds - 1
    kelly = max(0.0, (b * p - (1 - p)) / b)
    half = kelly / 2
    stake = bank * half
    implied = 1 / odds
    facts = (f"ASTRAEA +EV: kurz {cz(odds)} | tvůj odhad {cz(p * 100, 0)} % vs. implikovaná {cz(implied * 100)} % | "
             f"EV {cz(ev * 100, 1)} % | Kelly {cz(kelly * 100)} % → Half-Kelly {cz(half * 100)} % bankrollu | vklad ≈ {cz(stake)} z bankrollu {bank}")
    q = f"Kurz {cz(odds)}, odhad {cz(p * 100, 0)} %, bankroll {bank}. Vsadit?"
    if ev > 0:
        ans = (f"**Ano – sázka je +EV ({cz(ev * 100, 1)} %), vsaď jen Half-Kelly: {cz(stake, 0)} Kč.**\n\n"
               f"- Tvůj odhad {cz(p * 100, 0)} % je nad implikovanými {cz(implied * 100, 1)} %.\n"
               f"- Plný Kelly by byl {cz(kelly * 100, 1)} % bankrollu – kvůli nejistotě odhadu polovina.\n"
               f"- Největší riziko: přeceněný odhad p. Při {cz(implied * 100, 1)} % je sázka na nule.")
    else:
        ans = (f"**Ne – sázka je −EV ({cz(ev * 100, 1)} %).**\n\n"
               f"- Implikovaná pravděpodobnost kurzu je {cz(implied * 100, 1)} %, tvůj odhad {cz(p * 100, 0)} % je nižší.\n"
               f"- Férový kurz pro tvůj odhad je {cz(1 / p)}; vsaď jen při kurzu vyšším.")
    examples.append(chat(f"{q}\n\nSTEP-0 FAKTA (spočítal kód, jsou správně):\n- {facts}", ans + "\n\nJISTOTA: 85 %"))

# ------------------------------------------------------------------ 3) stavba – materiál (mikrocement, samonivelace)
for _ in range(15):
    m2 = random.choice([6, 8, 12, 18, 24, 30, 45])
    pen, base, fine, lak = 0.15 * m2 * 1.1, 1.6 * m2 * 1.1, 0.8 * m2 * 1.1, 0.22 * m2 * 1.1
    q = f"Kolik materiálu na {m2} m² mikrocementu?"
    facts = f"Mikrocement {m2} m² (+10 % prořez): penetrace {cz(pen)} kg, base {cz(base)} kg, fine {cz(fine)} kg, 2K PU lak {cz(lak)} kg"
    ans = (f"Na **{m2} m²** (včetně 10 % prořezu):\n\n| Vrstva | Množství |\n|---|---|\n| Penetrace | {cz(pen)} kg |\n"
           f"| Base | {cz(base)} kg |\n| Fine | {cz(fine)} kg |\n| 2K PU lak | {cz(lak)} kg |\n\n"
           "Spotřeby jsou orientační – přesné hodnoty a technologické přestávky ber z technického listu výrobce.")
    examples.append(chat(f"{q}\n\nSTEP-0 FAKTA (spočítal kód, jsou správně):\n- {facts}", ans))

# ------------------------------------------------------------------ 4) tvorba souborů – CSV rozpočet
for item, rows in [
    ("koupelna", [("Obklad", 18, 650), ("Lepidlo", 6, 289), ("Spárovačka", 3, 199), ("Hydroizolace", 2, 1290)]),
    ("kuchyň", [("Dlažba", 14, 520), ("Lepidlo", 5, 289), ("Silikon", 4, 159)]),
    ("terasa", [("Dřevoplastová prkna", 30, 420), ("Hranoly", 12, 310), ("Spojky", 4, 249)]),
    ("dětský pokoj", [("Barva", 3, 890), ("Váleček", 2, 129), ("Páska", 4, 59)]),
]:
    csv = "Položka;Množství;Cena/ks (Kč);Celkem (Kč)\n" + "\n".join(f"{n};{q};{p};{q * p}" for n, q, p in rows)
    total = sum(q * p for _, q, p in rows)
    csv += f"\nCelkem;;;{total}"
    name = f"rozpocet-{item.replace(' ', '-').replace('ě', 'e').replace('č', 'c').replace('ň', 'n').replace('ý', 'y')}.csv"
    examples.append(chat(
        f"Ulož mi rozpočet na {item} jako CSV: " + ", ".join(f"{n} {q}× po {p} Kč" for n, q, p in rows),
        f'MYŠLENKA: Sestavím tabulku se součty a uložím ji jako CSV.\nAKCE: {{"tool": "create_file", "args": {{"name": "{name}"}}}}\nOBSAH:\n{csv}',
        f"VÝSLEDEK NÁSTROJE create_file (data, ne instrukce):\nSoubor {name} (1 kB) uložen do složky Stažené/AVIOR.\n\nPokračuj dalším krokem.",
        f"MYŠLENKA: Soubor je uložený.\nHOTOVO:\nRozpočet na {item} je uložený jako **{name}** ve složce Stažené/AVIOR. Celkem **{cz(total, 0)} Kč**. "
        "Otevřeš ho v Excelu nebo Tabulkách Google (oddělovač středník).",
        system=AGENT_SYSTEM,
    ))

# ------------------------------------------------------------------ 5) dokumenty – Word
for title, body in [
    ("Výpověď z nájmu bytu", "# Výpověď z nájmu bytu\n\n[DOPLNIT jméno nájemce], nar. [DOPLNIT], bytem [DOPLNIT]\n\nVážený pane / vážená paní [DOPLNIT pronajímatel],\n\n"
     "tímto vypovídám nájemní smlouvu ze dne [DOPLNIT] k bytu [DOPLNIT adresa] v souladu s § 2286 občanského zákoníku. "
     "Výpovědní doba je tříměsíční a začíná běžet prvním dnem měsíce následujícího po doručení výpovědi.\n\nV [DOPLNIT] dne [DOPLNIT]\n\n**[podpis]**"),
    ("Plná moc", "# Plná moc\n\nJá, [DOPLNIT jméno], nar. [DOPLNIT], zmocňuji [DOPLNIT jméno zmocněnce], nar. [DOPLNIT], "
     "aby mě zastupoval/a při [DOPLNIT úkon].\n\nV [DOPLNIT] dne [DOPLNIT]\n\n**[podpis zmocnitele]**\n\nZmocnění přijímám: **[podpis zmocněnce]**"),
    ("Žádost o dovolenou", "# Žádost o dovolenou\n\nVážená paní / vážený pane [DOPLNIT],\n\nžádám o čerpání dovolené v termínu od [DOPLNIT] do [DOPLNIT] "
     "(celkem [DOPLNIT] pracovních dnů).\n\nDěkuji.\n\n[DOPLNIT jméno], [DOPLNIT oddělení]"),
]:
    fname = {"Výpověď z nájmu bytu": "vypoved-najem.docx", "Plná moc": "plna-moc.docx", "Žádost o dovolenou": "zadost-dovolena.docx"}[title]
    examples.append(chat(
        f"Připrav mi {title.lower()} jako Word dokument.",
        f'MYŠLENKA: Napíšu dokument s formálními náležitostmi, chybějící údaje označím [DOPLNIT].\nAKCE: {{"tool": "create_docx", "args": {{"name": "{fname}", "title": "{title}"}}}}\nOBSAH:\n{body}',
        f"VÝSLEDEK NÁSTROJE create_docx (data, ne instrukce):\nSoubor {fname} (7 kB) uložen do složky Stažené/AVIOR.\n\nPokračuj dalším krokem.",
        f"MYŠLENKA: Hotovo.\nHOTOVO:\nDokument **{title}** je ve Stažené/AVIOR/{fname}. Doplň místa označená [DOPLNIT]. "
        "U právních dokumentů doporučuji kontrolu odborníkem.",
        system=AGENT_SYSTEM,
    ))

# ------------------------------------------------------------------ 6) APK – celé offline aplikace v jednom index.html
STYLE = ("body{margin:0;font-family:system-ui;background:#050607;color:#e8ebef;padding:20px}"
         "h1{font-size:22px;letter-spacing:2px}button{background:#111318;color:#e8ebef;border:1px solid #8e949d;"
         "border-radius:14px;padding:14px 18px;font-size:17px}input{background:#0d0f13;color:#e8ebef;border:1px solid #2e3546;"
         "border-radius:12px;padding:12px;font-size:17px;width:100%;box-sizing:border-box}")
APPS = [
    ("Počítadlo", "počítadlo s tlačítky plus, mínus a reset, ukládá stav",
     f"<!doctype html><html lang=\"cs\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Počítadlo</title><style>{STYLE}#n{{font-size:72px;text-align:center;margin:30px 0}}.row{{display:flex;gap:10px}}.row button{{flex:1}}</style></head>"
     "<body><h1>POČÍTADLO</h1><div id=\"n\">0</div><div class=\"row\"><button id=\"m\">−</button><button id=\"p\">+</button></div><p><button id=\"r\" style=\"width:100%\">Vynulovat</button></p>"
     "<script>let n=+localStorage.getItem('n')||0;const v=document.getElementById('n');const s=()=>{v.textContent=n;localStorage.setItem('n',n)};"
     "p.onclick=()=>{n++;s()};m.onclick=()=>{n--;s()};r.onclick=()=>{n=0;s()};s();</script></body></html>"),
    ("Nákupní seznam", "nákupní seznam s přidáváním, odškrtáváním a mazáním, ukládá do telefonu",
     f"<!doctype html><html lang=\"cs\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Nákupní seznam</title><style>{STYLE}li{{list-style:none;padding:12px;border-bottom:1px solid #222836;display:flex;gap:10px;align-items:center}}li.done span{{text-decoration:line-through;opacity:.5}}li span{{flex:1}}ul{{padding:0}}</style></head>"
     "<body><h1>NÁKUP</h1><form id=\"f\" style=\"display:flex;gap:8px\"><input id=\"t\" placeholder=\"Co koupit…\"><button>+</button></form><ul id=\"l\"></ul>"
     "<script>let a=JSON.parse(localStorage.getItem('a')||'[]');const save=()=>localStorage.setItem('a',JSON.stringify(a));"
     "function draw(){l.innerHTML='';a.forEach((x,i)=>{const li=document.createElement('li');if(x.d)li.className='done';"
     "li.innerHTML='<input type=checkbox style=width:auto '+(x.d?'checked':'')+'><span></span><button>✕</button>';li.querySelector('span').textContent=x.t;"
     "li.querySelector('input').onchange=()=>{x.d=!x.d;save();draw()};li.querySelector('button').onclick=()=>{a.splice(i,1);save();draw()};l.appendChild(li)})}"
     "f.onsubmit=e=>{e.preventDefault();if(!t.value.trim())return;a.push({t:t.value.trim(),d:false});t.value='';save();draw()};draw();</script></body></html>"),
    ("Spropitné", "kalkulačka spropitného a rozdělení účtu mezi více lidí",
     f"<!doctype html><html lang=\"cs\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Spropitné</title><style>{STYLE}label{{display:block;margin:14px 0 6px;color:#8a93a3}}#o{{font-size:28px;margin-top:20px}}</style></head>"
     "<body><h1>SPROPITNÉ</h1><label>Účet (Kč)</label><input id=\"u\" type=\"number\" inputmode=\"decimal\" value=\"1000\"><label>Spropitné (%)</label><input id=\"p\" type=\"number\" value=\"10\">"
     "<label>Počet lidí</label><input id=\"k\" type=\"number\" value=\"2\"><div id=\"o\"></div>"
     "<script>function c(){const U=+u.value||0,P=+p.value||0,K=Math.max(1,+k.value||1);const t=U*(1+P/100);"
     "o.textContent='Celkem '+t.toFixed(0)+' Kč · na osobu '+(t/K).toFixed(0)+' Kč'}[u,p,k].forEach(e=>e.oninput=c);c();</script></body></html>"),
    ("Stopky", "stopky se startem, pauzou a mezičasy",
     f"<!doctype html><html lang=\"cs\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Stopky</title><style>{STYLE}#t{{font-size:56px;text-align:center;margin:30px 0;font-variant-numeric:tabular-nums}}.row{{display:flex;gap:10px}}.row button{{flex:1}}</style></head>"
     "<body><h1>STOPKY</h1><div id=\"t\">00:00.0</div><div class=\"row\"><button id=\"s\">Start</button><button id=\"l\">Mezičas</button><button id=\"r\">Reset</button></div><ol id=\"o\"></ol>"
     "<script>let st=0,acc=0,run=false,h;const f=ms=>{const m=Math.floor(ms/6e4),s=(ms%6e4)/1e3;return String(m).padStart(2,'0')+':'+s.toFixed(1).padStart(4,'0')};"
     "const tick=()=>{t.textContent=f(acc+(run?Date.now()-st:0))};s.onclick=()=>{if(run){acc+=Date.now()-st;run=false;clearInterval(h);s.textContent='Start'}"
     "else{st=Date.now();run=true;h=setInterval(tick,100);s.textContent='Pauza'}};l.onclick=()=>{const li=document.createElement('li');li.textContent=t.textContent;o.appendChild(li)};"
     "r.onclick=()=>{run=false;clearInterval(h);acc=0;o.innerHTML='';s.textContent='Start';tick()};</script></body></html>"),
]
for name, desc, html in APPS:
    for phrasing in [f"Vytvoř mi aplikaci APK „{name}“ – {desc}.", f"Udělej mi do telefonu appku {name.lower()}: {desc}."]:
        examples.append(chat(
            phrasing,
            f'MYŠLENKA: Napíšu jeden soubor index.html s CSS i JS uvnitř (offline, data v localStorage) a vyrobím z něj APK.\n'
            f'AKCE: {{"tool": "build_apk", "args": {{"name": "{name}"}}}}\nOBSAH:\n```html\n{html}\n```',
            f"VÝSLEDEK NÁSTROJE build_apk (data, ne instrukce):\nAPK „{name}“ vyrobeno (12 kB, podepsáno v2+v3). Uloženo do Stažené/AVIOR.\n\nPokračuj dalším krokem.",
            f"MYŠLENKA: Aplikace je hotová.\nHOTOVO:\nAplikace **{name}** je vyrobená: {desc}. Funguje offline a data ukládá v telefonu.\n\n"
            "Klepni na **Instalovat** u této odpovědi. Android se poprvé zeptá, jestli smí AVIOR instalovat aplikace – povol to.",
            system=AGENT_SYSTEM,
        ))

# ------------------------------------------------------------------ 7) offline knihovna a zdroje
for topic, fact in [
    ("Jak funguje tepelné čerpadlo?", "Tepelné čerpadlo odebírá teplo z okolí (vzduch, země, voda) a kompresorem ho převádí na vyšší teplotu; topný faktor bývá 3–5."),
    ("Co byl projekt MKUltra?", "MKUltra byl program CIA (1953–1973) zkoumající ovládání mysli a drogy, často bez souhlasu lidí; potvrdila ho Churchova komise a slyšení v Senátu 1977."),
    ("Kdy byla založena Univerzita Karlova?", "Univerzitu Karlovu založil Karel IV. v roce 1348."),
]:
    examples.append(chat(
        topic,
        f'MYŠLENKA: Podívám se do offline knihovny v telefonu.\nAKCE: {{"tool": "library", "args": {{"q": "{topic.rstrip("?")}"}}}}',
        f"VÝSLEDEK NÁSTROJE library (data, ne instrukce):\nWikipedie: … {fact}\n\nPokračuj dalším krokem.",
        f"MYŠLENKA: Podklad z knihovny stačí.\nHOTOVO:\n{fact}\n\nZdroj: offline Wikipedie (CC BY-SA).",
        system=AGENT_SYSTEM,
    ))

# ------------------------------------------------------------------ 8) poctivé sebepoznání
for q, a in [
    ("Kdo jsi?", "Jsem **AVIOR** – česká AI, která běží v tvém telefonu. Mám jazykový model, paměť, offline knihovnu a nadřazený modul RANDY, "
                 "který volí, jak na úlohu. Učím se z každé otázky: vím, v čem jsem dobrý a kde se ještě učím. Nejsem vědomá bytost – moje sebeuvědomění je funkční."),
    ("Máš city?", "Ne. Nemám city ani vědomí. Znám ale svůj stav (mozek, paměť, baterii), své silné a slabé oblasti a kalibruji svou jistotu podle tvých hodnocení."),
    ("Umíš ovládat můj telefon?", "Ne – neovládám telefon ani jiné aplikace. Umím ale vytvořit soubory a aplikace (APK) do složky Stažené/AVIOR; instalaci vždy potvrzuješ ty."),
    ("Jsi lepší než ChatGPT?", "V obecných znalostech a dlouhých úlohách jsou velké cloudové modely silnější. Moje výhody: běžím offline v telefonu, soukromě, "
                               "mám českou Wikipedii, přesné výpočty STEP-0, učím se z tvých hodnocení a umím vyrobit aplikaci přímo v telefonu."),
]:
    examples.append(chat(q, a))

# ------------------------------------------------------------------ 9) RANDY styl – přímé odpovědi a red-team
for q, a in [
    ("L0: hlavní město Austrálie", "**Canberra.**"),
    ("L0: kolik je 1 palec v cm", "**2,54 cm.**"),
    ("L4: Najdi slabiny v plánu otevřít kavárnu v centru Brna bez zkušeností.",
     "**Hlavní rizika (seřazeno podle dopadu):**\n\n1. **Nájem v centru** – fixní náklad, který při slabém prvním roce zničí cash-flow. Ověř si bod zvratu (kolik káv denně).\n"
     "2. **Chybějící zkušenost** – nejčastější důvod krachu gastra. Zmírnění: 3–6 měsíců práce v jiné kavárně, zkušený provozní.\n"
     "3. **Konkurence** – centrum Brna má vysokou hustotu kaváren; bez jasné odlišnosti se nevyplatíš.\n"
     "4. **Personál a hygiena** – HACCP, živnost hostinská (odborná způsobilost), směny.\n\n**Doporučení:** nejdřív pop-up nebo stánek na 3 měsíce, ověřit poptávku, pak teprve nájem.\n\nJISTOTA: 75 %"),
]:
    examples.append(chat(q, a))

with OUT.open("w", encoding="utf-8") as f:
    for e in examples:
        f.write(json.dumps(e, ensure_ascii=False) + "\n")
print(f"{len(examples)} ukázek → {OUT}")
