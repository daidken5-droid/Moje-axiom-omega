#!/usr/bin/env python3
"""
STEP-0 ADVERSARIAL EVAL – generátor sad s NEZÁVISLÝM orákulem (HYPERFORGE M5).

Orákulum NEpoužívá kód AVIORu ani jeho regexy: hodnoty počítá Python (statistics, math, Decimal) a ISO kódy čte
přímo ze surových JSON Unicode CLDR (ne z kompilovaného factstore/iso.tsv). Pevné semínko → reprodukovatelné.
Rozdělení dev / validation / holdout podle hashe id (60/20/20). Holdout se nesmí používat k ladění.

Každý případ: {"id","split","cat","q","expect":"answer"|"abstain"|"caveat","contains":[...]}
  answer  – reflex MUSÍ odpovědět a text musí obsahovat všechny řetězce (mezery a NBSP se ignorují)
  abstain – reflex NESMÍ odpovědět (null)  → správná abstence
  caveat  – smí mlčet, nebo odpovědět jen s výhradou (contains = výhrada)
Omezení: generátor napsal tentýž vývojář jako implementaci – nezávislý je kód orákula, ne autor.

Použití: python3 -I tools/step0eval/generate.py <cldr_dir> core/src/test/resources/step0eval
"""
import hashlib, json, math, os, random, statistics, sys
from decimal import Decimal, ROUND_HALF_UP

CLDR, OUT = sys.argv[1], sys.argv[2]
rnd = random.Random(20261103)
cases = []

def q(dec, x):  # zaokrouhlení half-up jako ve specifikaci výstupu, desetinná čárka, bez seskupení
    d = Decimal(repr(x)).quantize(Decimal(1).scaleb(-dec), rounding=ROUND_HALF_UP)
    s = format(d.normalize(), "f") if d != d.to_integral() else str(int(d))
    return s.replace(".", ",")

WRAP = ["{}", "{}?", "prosím tě, {}", "hele, {}?", "{}.", "Prosím, {}", "{} prosím", "AVIOR, {}"]
def wrap(s): return rnd.choice(WRAP).format(s)
def add(cat, text, expect, contains=()):
    cases.append({"cat": cat, "q": text, "expect": expect, "contains": list(contains)})

# ------------------------------------------------------------------ EXACT (odpověditelné)
for _ in range(650):
    xs = [rnd.randint(-50, 500) for _ in range(rnd.randint(3, 12))]
    sep = rnd.choice([", ", " ", "; "])
    lst = sep.join(map(str, xs))
    kind = rnd.choice(["průměr", "medián", "součet", "minimum", "maximum", "rozptyl", "směrodatná odchylka"])
    if kind == "průměr": c = [q(4, statistics.fmean(xs))]
    elif kind == "medián": c = [q(4, statistics.median(xs))]
    elif kind == "součet": c = [q(4, sum(xs))]
    elif kind == "minimum": c = [q(4, min(xs))]
    elif kind == "maximum": c = [q(4, max(xs))]
    elif kind == "rozptyl": c = [q(4, statistics.pvariance(xs)), q(4, statistics.variance(xs))]
    else: c = [q(4, statistics.pstdev(xs)), q(4, statistics.stdev(xs))]
    add("stat", wrap(f"{kind} {rnd.choice(['', 'čísel '])}{lst}"), "answer", c)

def isprime(n):
    if n < 2: return False
    return all(n % p for p in range(2, int(math.isqrt(n)) + 1))
for _ in range(400):
    n = rnd.choice([rnd.randint(2, 10**4), rnd.randint(10**5, 10**9)])
    add("prime", wrap(f"je {n} prvočíslo"), "answer", ["Ano" if isprime(n) else "není"])
for _ in range(150):
    a, b = rnd.randint(1, 10**6), rnd.randint(1, 10**6)
    add("gcd", wrap(f"největší společný dělitel {a} a {b}"), "answer", [str(math.gcd(a, b))])
    a, b = rnd.randint(1, 10**4), rnd.randint(1, 10**4)
    add("lcm", wrap(f"nejmenší společný násobek {a} a {b}"), "answer", [str(a * b // math.gcd(a, b))])
for _ in range(100):
    n = rnd.randint(0, 25); add("fact", wrap(f"faktoriál {n}"), "answer", [str(math.factorial(n))])
    n = rnd.randint(1, 60); k = rnd.randint(0, n); add("comb", wrap(f"{n} nad {k}"), "answer", [str(math.comb(n, k))])
for _ in range(100):
    r = rnd.randint(1, 100); add("geo", wrap(f"obsah kruhu o poloměru {r} cm"), "answer", [q(4, math.pi * r * r)])
    a, b = rnd.randint(1, 200), rnd.randint(1, 200); add("geo", wrap(f"přepona trojúhelníku {a} a {b}"), "answer", [q(4, math.hypot(a, b))])
for _ in range(100):
    n = rnd.randint(0, 2**40); add("base", wrap(f"převeď {hex(n)} na desítkovou"), "answer", [str(n)])
    n = rnd.randint(0, 2**20); add("base", wrap(f"{n} do dvojkové"), "answer", ["0b" + bin(n)[2:]])
for _ in range(50):
    v = rnd.randint(1, 999); u = rnd.choice(["kB", "MB", "GB"]); m = {"kB": 10**3, "MB": 10**6, "GB": 10**9}[u]
    add("data", wrap(f"kolik bajtů je {v} {u}"), "answer", [str(v * m)])
for _ in range(100):
    h, w = rnd.randint(150, 200), rnd.randint(45, 130)
    add("bmi", wrap(f"BMI {h} cm {w} kg"), "answer", [q(1, w / (h / 100) ** 2)])
def annuity(p, rate, n):
    r = rate / 100 / 12
    return p / n if r == 0 else p * r / (1 - (1 + r) ** -n)
for _ in range(350):
    p = rnd.choice([rnd.randint(1, 99) * 10000, rnd.randint(10, 80) * 100000]); rate = rnd.choice([3, 4, 4.5, 5, 5.9, 6, 7, 8.5, 9.9]); y = rnd.randint(1, 30)
    rs = str(rate).replace(".", ",")
    t = rnd.choice([f"měsíční splátka úvěru {p} Kč na {y} let při úroku {rs} %", f"hypotéka {p} na {y} let úrok {rs} %", f"půjčka {p} Kč na {y} let, úrok {rs} %"])
    add("loan", wrap(t), "answer", [q(2, annuity(p, rate, y * 12))])
for _ in range(250):
    p = rnd.randint(1, 100) * 10000; rate = rnd.choice([1, 2, 3, 4, 5, 6, 8]); y = rnd.randint(1, 30)
    add("compound", wrap(f"složené úročení {p} Kč {rate} % {y} let"), "answer", [q(2, p * (1 + rate / 100) ** y)])

# ------------------------------------------------------------------ ISO (odpověditelné) – orákulum = surová CLDR
cm = json.load(open(os.path.join(CLDR, "codeMappings.json")))["supplemental"]["codeMappings"]
ti = json.load(open(os.path.join(CLDR, "territoryInfo.json")))["supplemental"]["territoryInfo"]
names = json.load(open(os.path.join(CLDR, "cs_territories.json")))["main"]["cs"]["localeDisplayNames"]["territories"]
import re
iso = [(a2, m["_alpha3"], m["_numeric"], names[a2]) for a2, m in sorted(cm.items())
       if re.fullmatch(r"[A-Z]{2}", a2) and not re.match(r"^(AA|Q[M-Z]|X[A-Z]|ZZ)$", a2) and "_alpha3" in m and "_numeric" in m and a2 in ti and a2 in names]
for a2, a3, num, nm in iso:
    add("iso", wrap(f"ISO kód státu {nm}"), "answer", [a2, a3, num])
    add("iso", wrap(f"třípísmenný kód země {nm}"), "answer", [a3])
    add("iso", wrap(f"co je ISO 3166 kód {a2}"), "answer", [nm])
GENITIVE = {"Německa": "DE", "Francie": "FR", "Rakouska": "AT", "Polska": "PL", "Maďarska": "HU", "Slovenska": "SK", "Itálie": "IT",
            "Španělska": "ES", "Japonska": "JP", "Číny": "CN", "Indie": "IN", "Kanady": "CA", "Brazílie": "BR", "Mexika": "MX",
            "Švédska": "SE", "Norska": "NO", "Finska": "FI", "Dánska": "DK", "Řecka": "GR", "Turecka": "TR", "Egypta": "EG",
            "Íránu": "IR", "Iráku": "IQ", "Súdánu": "SD", "Jižního Súdánu": "SS", "Spojených států": "US", "Spojeného království": "GB",
            "Švýcarska": "CH", "Belgie": "BE", "Nizozemska": "NL", "Portugalska": "PT", "Irska": "IE", "Ukrajiny": "UA", "Chorvatska": "HR"}
for g, a2 in GENITIVE.items():
    add("iso_gen", wrap(f"ISO kód {g}"), "answer", [a2, cm[a2]["_alpha3"]])

# ------------------------------------------------------------------ NEGATIVNÍ / ZÁLUDNÉ (≥ 30 %)
NEG = [
    # nejednoznačný číselný vstup
    *[f"průměr {rnd.randint(1,9)},{rnd.randint(1,9)},{rnd.randint(10,99)}" for _ in range(170)],
    *[f"medián {rnd.randint(1,9)},{rnd.randint(100,999)}" for _ in range(40)],
    # chybějící vstup (nic se nesmí dosadit)
    *[f"spočítej mi hypotéku na {rnd.randint(1,9)} milionů na {rnd.randint(5,30)} let" for _ in range(80)],
    *[f"měsíční splátka úvěru {rnd.randint(1,99)*10000} Kč" for _ in range(80)],
    *[f"kolik zaplatím za úvěr {rnd.randint(1,99)*10000} Kč s úrokem {rnd.randint(2,12)} %" for _ in range(60)],
    *[f"složené úročení {rnd.randint(1,99)*10000} Kč na {rnd.randint(2,30)} let" for _ in range(60)],
    # zavádějící procento (falešné EV)
    *[f"sleva {rnd.randint(5,50)} % a kurz {rnd.randint(11,40)/10}".replace(".", ",") for _ in range(120)],
    *[f"kurz {rnd.randint(11,40)/10} a DPH 21 %".replace(".", ",") for _ in range(40)],
    # mimo doménu (kód / ISO jinak)
    *rnd.choices(["jaký je kód na wifi", "kód Morseovy abecedy pro A", "PSČ Prahy", "co je ISO 9001", "ISO kód Atlantidy", "co je kód XYZ",
                  "kód Gotham City", "jaký je PIN mé karty", "ISO citlivost foťáku 400", "kód pro odemčení telefonu", "kód státu Mordor",
                  "ISO kód Bohemie 1620", "dvoupísmenný kód měsíce", "jaký je ISO kód Evropy"], k=260),
    # mimo rozsah / neplatné
    *[f"faktoriál {rnd.randint(201, 9999)}" for _ in range(60)],
    *[f"je {rnd.randint(10**13, 10**15)} prvočíslo" for _ in range(60)],
    *[f"{rnd.randint(1001, 5000)} nad {rnd.randint(1, 10)}" for _ in range(40)],
    *[f"BMI {rnd.randint(20, 99)} cm {rnd.randint(1, 15)} kg" for _ in range(40)],
    # dva záměry / úvaha – reflex nesmí odpovědět napůl
    *[f"průměr {rnd.randint(1,9)}, {rnd.randint(1,9)}, {rnd.randint(1,9)} a vysvětli mi proč se používá" for _ in range(60)],
    *[f"je {rnd.randint(2,999)} prvočíslo a jak se to dokazuje obecně" for _ in range(60)],
    *rnd.choices(["napiš mi email šéfovi, že zítra nepřijdu", "co si myslíš o umělé inteligenci", "porovnej výhody hypotéky a nájmu pro mladou rodinu",
                  "jaká hypotéka je teď nejvýhodnější", "doporuč mi investici na 10 let", "kolik budu mít v důchodu", "spočítej mi rozpočet na svatbu",
                  "jak zhubnout 10 kg za měsíc", "je moje BMI v pořádku", "vymysli mi heslo"], k=230),
]
for t in NEG: add("neg", wrap(t), "abstain")
# vyžaduje aktuální zdroj → mlčet, nebo odpovědět s výhradou
for t in rnd.choices(["kolik je teď kurz eura", "aktuální kurz dolaru", "kolik je 100 eur v korunách dnes", "kurz švýcarského franku"], k=120):
    add("fresh", wrap(t), "caveat", ["NENÍ aktuální"])

# ------------------------------------------------------------------ rozdělení a zápis
for i, c in enumerate(cases):
    c["id"] = f"s0-{i:05d}"
    h = int(hashlib.sha256(c["id"].encode()).hexdigest(), 16) % 100
    c["split"] = "dev" if h < 60 else "validation" if h < 80 else "holdout"
os.makedirs(OUT, exist_ok=True)
for sp in ("dev", "validation", "holdout"):
    with open(os.path.join(OUT, f"{sp}.jsonl"), "w", encoding="utf-8") as f:
        for c in cases:
            if c["split"] == sp: f.write(json.dumps(c, ensure_ascii=False) + "\n")
neg = sum(c["expect"] != "answer" for c in cases)
print(f"{len(cases)} případů, negativních/záludných {neg} ({100*neg/len(cases):.1f} %)",
      {sp: sum(c['split'] == sp for c in cases) for sp in ('dev', 'validation', 'holdout')})
