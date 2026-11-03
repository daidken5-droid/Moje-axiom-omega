#!/usr/bin/env python3
"""
AVIOR DATA COMPILER (HYPERFORGE M4) – vertikál „ISO 3166-1 + ISO 4217“ z Unicode CLDR.

Opakovatelná pipeline:  připnuté zdroje (commit + SHA-256)  →  validace schématu  →  detekce konfliktů / duplicit
→  zlaté vzorky (nezávislé na pipeline)  →  kompaktní TSV pro Verified Fact Store  →  manifest s kontrolním součtem.

Použití:
  python3 -I tools/factc/compile_cldr_iso.py <adresář_se_staženými_zdroji> core/src/main/resources/factstore
Zdroje (stáhne tools/factc/fetch_cldr.sh): codeMappings.json, currencyData.json, territoryInfo.json,
cs_territories.json, currencies.json, LICENSE.  Licence: Unicode License v3 (oznámení jde s daty).
Selhání validace = exit 1 a NIC se nezapíše.
"""
import hashlib, json, os, re, sys, unicodedata

SRC, OUT = sys.argv[1], sys.argv[2]
CLDR_COMMIT = open(os.path.join(SRC, "cldr.sha")).read().strip()
AS_OF = "2026-11-03"            # den sestavení (data platí k vydání CLDR uvedenému v manifestu)
VALID_CODES = "2029-11-03"      # kódy ISO jsou stabilní → revize po 3 letech
VALID_CURRENCY = "2027-11-03"   # měna státu se může změnit (přijetí eura) → revize po roce
LICENSE = "Unicode-3.0"

def sha(p): return hashlib.sha256(open(p, "rb").read()).hexdigest()
def load(n): return json.load(open(os.path.join(SRC, n), encoding="utf-8"))

files = ["codeMappings.json", "currencyData.json", "territoryInfo.json", "cs_territories.json", "currencies.json", "LICENSE"]
src_hash = {f: sha(os.path.join(SRC, f)) for f in files}
cm = load("codeMappings.json")["supplemental"]
cldr_version = cm["version"]["_cldrVersion"]
codes = cm["codeMappings"]
cur = load("currencyData.json")["supplemental"]["currencyData"]["region"]
tinfo = load("territoryInfo.json")["supplemental"]["territoryInfo"]
names = load("cs_territories.json")["main"]["cs"]["localeDisplayNames"]["territories"]
curnames = load("currencies.json")["main"]["cs"]["numbers"]["currencies"]
SOURCE = f"Unicode CLDR {cldr_version} (cldr-json@{CLDR_COMMIT[:12]})"

errors = []
PRIVATE = re.compile(r"^(AA|Q[M-Z]|X[A-Z]|ZZ)$")
rows = []
def add(entity, pred, value, lang, valid, rel):
    body = f"{entity}|{pred}|{value}"
    rows.append([f"{entity}:{pred}", entity, pred, value, lang, SOURCE, AS_OF, valid, rel, LICENSE, "1",
                 hashlib.sha256(body.encode()).hexdigest()[:16]])

countries = []
for a2, m in sorted(codes.items()):
    # jen současné území ISO 3166-1: 2 písmena, alpha-3 i číselný kód, údaje o území, ne soukromý rozsah
    if not re.fullmatch(r"[A-Z]{2}", a2) or PRIVATE.match(a2): continue
    if "_alpha3" not in m or "_numeric" not in m or a2 not in tinfo: continue
    if a2 not in names: errors.append(f"{a2}: chybí český název"); continue
    countries.append(a2)

seen = {}
for a2 in countries:
    m = codes[a2]; e = f"iso3166:{a2}"
    a3, num = m["_alpha3"], m["_numeric"]
    for k, v, rx in (("alpha2", a2, r"[A-Z]{2}"), ("alpha3", a3, r"[A-Z]{3}"), ("numeric", num, r"\d{3}")):
        if not re.fullmatch(rx, v): errors.append(f"{a2}: {k}={v} neodpovídá schématu")
        if (k, v) in seen: errors.append(f"KONFLIKT {k}={v}: {seen[(k, v)]} a {a2}")
        seen[(k, v)] = a2
        add(e, k, v, "und", VALID_CODES, "A-standard")
    add(e, "name_cs", names[a2], "cs", VALID_CODES, "A-standard")
    for alt in ("-alt-short", "-alt-variant"):
        if a2 + alt in names: add(e, "alias_cs", names[a2 + alt], "cs", VALID_CODES, "A-standard")
    # aktuální měna: záznam bez _to, zákonné platidlo, nejnovější _from
    cands = [(list(x.keys())[0], list(x.values())[0]) for x in cur.get(a2, [])]
    cands = [(c, d) for c, d in cands if "_to" not in d and d.get("_tender", "true") != "false"]
    if cands:
        c, d = max(cands, key=lambda t: t[1].get("_from", ""))
        if not re.fullmatch(r"[A-Z]{3}", c): errors.append(f"{a2}: měna {c} neodpovídá schématu")
        add(e, "currency", c, "und", VALID_CURRENCY, "A-standard")
        if c in curnames: add(e, "currency_name_cs", curnames[c]["displayName"], "cs", VALID_CURRENCY, "A-standard")
        if d.get("_from"): add(e, "currency_since", d["_from"], "und", VALID_CURRENCY, "A-standard")

# duplicity (entita, predikát) – kromě aliasů
keyset = {}
for r in rows:
    if r[2] == "alias_cs": continue
    if (r[1], r[2]) in keyset: errors.append(f"DUPLICITA {r[1]} {r[2]}")
    keyset[(r[1], r[2])] = r[3]

# ZLATÉ VZORKY – nezávislé na pipeline (ručně známé hodnoty ISO); nesoulad = chyba zdroje nebo pipeline
GOLD = {"CZ": ("CZE", "203", "CZK", "Česko"), "SK": ("SVK", "703", "EUR", "Slovensko"), "DE": ("DEU", "276", "EUR", "Německo"),
        "US": ("USA", "840", "USD", None), "JP": ("JPN", "392", "JPY", "Japonsko"), "GB": ("GBR", "826", "GBP", None),
        "CH": ("CHE", "756", "CHF", "Švýcarsko"), "PL": ("POL", "616", "PLN", "Polsko"), "FR": ("FRA", "250", "EUR", "Francie"),
        "AT": ("AUT", "040", "EUR", "Rakousko"), "HU": ("HUN", "348", "HUF", "Maďarsko"), "UA": ("UKR", "804", "UAH", "Ukrajina")}
for a2, (a3, num, c, nm) in GOLD.items():
    e = f"iso3166:{a2}"
    got = (keyset.get((e, "alpha3")), keyset.get((e, "numeric")), keyset.get((e, "currency")))
    if got != (a3, num, c): errors.append(f"ZLATÝ VZOREK {a2}: čekal {(a3, num, c)}, je {got}")
    if nm and keyset.get((e, "name_cs")) != nm: errors.append(f"ZLATÝ VZOREK {a2}: název {keyset.get((e, 'name_cs'))} ≠ {nm}")

# nejednoznačné české názvy (stejný název dvou entit) → zapsat do manifestu; reflex u nich ODMÍTNE odpovědět
def fold(s): return "".join(ch for ch in unicodedata.normalize("NFD", s.lower()) if unicodedata.category(ch) != "Mn")
by_name = {}
for r in rows:
    if r[2] in ("name_cs", "alias_cs"): by_name.setdefault(fold(r[3]), set()).add(r[1])
ambiguous = sorted(n for n, es in by_name.items() if len(es) > 1)

if errors:
    print("VALIDACE SELHALA:", *errors, sep="\n  "); sys.exit(1)

os.makedirs(OUT, exist_ok=True)
header = ["factId", "entityId", "predicate", "value", "language", "source", "asOf", "validUntil", "reliabilityClass", "license", "revision", "checksum"]
body = "\t".join(header) + "\n" + "".join("\t".join(r) + "\n" for r in rows)
tsv = os.path.join(OUT, "iso.tsv")
open(tsv, "w", encoding="utf-8", newline="\n").write(body)
manifest = {"vertical": "ISO 3166-1 + ISO 4217", "file": "iso.tsv", "sha256": hashlib.sha256(body.encode()).hexdigest(),
            "rows": len(rows), "entities": len(countries), "cldr": cldr_version, "cldrCommit": CLDR_COMMIT, "asOf": AS_OF,
            "license": LICENSE, "sources": src_hash, "ambiguousNames": ambiguous, "golden": sorted(GOLD)}
open(os.path.join(OUT, "iso.manifest.json"), "w", encoding="utf-8").write(json.dumps(manifest, ensure_ascii=False, indent=1) + "\n")
lic = open(os.path.join(SRC, "LICENSE"), encoding="utf-8").read()
open(os.path.join(OUT, "LICENSE-Unicode-CLDR.txt"), "w", encoding="utf-8").write(lic)
print(f"OK: {len(countries)} území, {len(rows)} faktů, sha256 {manifest['sha256'][:16]}…, nejednoznačné názvy: {ambiguous}")
