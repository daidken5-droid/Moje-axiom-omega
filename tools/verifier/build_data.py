"""Sestaví dataset: dělení PODLE DOKUMENTŮ (rodiny parafrází a překlady stejné věci zůstanou v jedné části) a teprve
pak syntetické varianty – jen z trénovacích dokumentů a výslovně označené. Klíč závěrečné sady jde do zvláštního souboru.
Použití: python build_data.py  (deterministické, seed 20261119)"""
import json, random, re, csv, hashlib, os
from seed_claims import SEED, S, C, N
random.seed(20261119)
H = os.path.dirname(os.path.abspath(__file__)); D = os.path.join(H, "data")
docs = json.load(open(os.path.join(D, "documents.json"), encoding="utf-8"))
# uzamčené závěrečné dokumenty (vyvážené jazykem i oblastí) a validační; zbytek trénink
FINAL = {"wiki-cs-45729", "wiki-cs-54077", "wiki-cs-200435", "wiki-en-2589713", "wiki-en-30011", "wiki-en-33583"}
VAL = {"wiki-cs-6250", "wiki-cs-357553", "wiki-en-662", "wiki-en-357353"}
def split_of(doc_id): return "final" if doc_id in FINAL else "val" if doc_id in VAL else "train"

rows = []
for i, (di, passage, claim, label, phen, fam) in enumerate(SEED):
    d = docs[di]
    # nezlomitelné mezery a typografické rozdíly: hledá se v normalizované kopii STEJNÉ délky (posun platí pro originál)
    norm = lambda t: t.replace("\u00a0", " ").replace("\u2009", " ").replace("\u202f", " ")
    off = norm(d["text"]).find(norm(passage))
    if off >= 0: passage = d["text"][off:off + len(passage)]
    assert off >= 0, f"pasáž není v dokumentu {d['doc_id']}: {passage[:60]}"
    rows.append({"id": f"s{i:04d}", "claim": claim, "passage": passage, "passage_lang": d["lang"], "doc_id": d["doc_id"], "doc_rev": d["revision"],
                 "char_start": off, "char_end": off + len(passage), "source_title": d["title"], "source_url": d["url"], "licence": d["licence"],
                 "label": label, "phenomena": phen, "family": f"{d['doc_id']}:{fam}", "topic": d["topic"], "split": split_of(d["doc_id"]),
                 "annotation_origin": "navrh_modelu", "status": "ceka_na_kontrolu", "synthetic": False,
                 "reason": "ruční návrh – čeká na lidskou kontrolu"})

# ---- syntetické varianty jen z trénovacích dokumentů (po rozdělení)
def neg(cl):
    for a, b in [(" je ", " není "), (" byl ", " nebyl "), (" byla ", " nebyla "), (" bylo ", " nebylo "), (" má ", " nemá "), (" leží ", " neleží "), (" vede ", " nevede "), (" spojuje ", " nespojuje ")]:
        if a in cl: return cl.replace(a, b, 1)
    return None
def num(cl):
    m = re.search(r"\b(\d{2,4})\b", cl)
    if not m: return None
    v = int(m.group(1)); w = v + random.choice([-7, -3, 2, 5, 11]) if v > 20 else v + random.choice([1, 2, 3])
    return cl[:m.start()] + str(w) + cl[m.end():]
syn = []
train_sup = [r for r in rows if r["split"] == "train" and r["label"] == S]
others = [r for r in rows if r["split"] == "train"]
for r in train_sup:
    for kind, f, lab, why in [("syn-zapor", neg, C, "zápor tvrzení podporovaného pasáží"), ("syn-cislo", num, C, "změněné číslo/datum uvedené v pasáži")]:
        v = f(r["claim"])
        if v and v != r["claim"]:
            syn.append({**r, "id": r["id"] + "-" + kind, "claim": v, "label": lab, "phenomena": [kind], "annotation_origin": "synteticky", "status": "synteticky", "synthetic": True, "reason": why})
    # plán × uskutečnění: pasáž říká, že se stalo – tvrzení, že to bylo jen plánováno, pasáž nedokládá
    syn.append({**r, "id": r["id"] + "-syn-plan", "claim": "Plánovalo se, že " + r["claim"][0].lower() + r["claim"][1:], "label": N, "phenomena": ["syn-plan"],
                "annotation_origin": "synteticky", "status": "synteticky", "synthetic": True, "reason": "pasáž uvádí skutečnost, ne plán"})
    # pasáž z jiného trénovacího dokumentu = nedostačuje
    o = random.choice([x for x in others if x["doc_id"] != r["doc_id"]])
    syn.append({**r, "id": r["id"] + "-syn-cizi", "passage": o["passage"], "passage_lang": o["passage_lang"], "doc_id": o["doc_id"], "doc_rev": o["doc_rev"], "char_start": o["char_start"], "char_end": o["char_end"],
                "source_title": o["source_title"], "source_url": o["source_url"], "label": N, "phenomena": ["syn-cizi"], "annotation_origin": "synteticky", "status": "synteticky", "synthetic": True, "reason": "pasáž z jiného dokumentu"})
allr = rows + syn
with open(os.path.join(D, "pairs.jsonl"), "w", encoding="utf-8") as f:
    for r in allr:
        if r["split"] != "final": f.write(json.dumps(r, ensure_ascii=False) + "\n")
# závěrečná sada: vstupy bez štítku + klíč zvlášť (trénink ani výběr prahu ho nečtou)
fin = [r for r in allr if r["split"] == "final"]
with open(os.path.join(D, "final_inputs.jsonl"), "w", encoding="utf-8") as f:
    for r in fin: f.write(json.dumps({k: v for k, v in r.items() if k not in ("label", "reason")}, ensure_ascii=False) + "\n")
os.makedirs(os.path.join(H, "locked"), exist_ok=True)
with open(os.path.join(H, "locked", "final_key.csv"), "w", encoding="utf-8", newline="") as f:
    w = csv.writer(f); w.writerow(["id", "label"]); [w.writerow([r["id"], r["label"]]) for r in fin]
# formulář pro anotátora (vše, co čeká na kontrolu)
with open(os.path.join(D, "annotation_queue.csv"), "w", encoding="utf-8", newline="") as f:
    w = csv.writer(f, delimiter=";"); w.writerow(["id", "tvrzeni", "pasaz_original", "jazyk_pasaze", "zdroj", "navrh_stitku", "rozhodnuti (PODPORUJE/ODPORUJE/NEDOSTAČUJE/VYŘADIT)", "poznamka"])
    for r in allr:
        if r["status"] == "ceka_na_kontrolu": w.writerow([r["id"], r["claim"], r["passage"], r["passage_lang"], r["source_url"] + "?oldid=" + r["doc_rev"], "" if r["split"] == "final" else r["label"], "", ""])
man = {"vytvoreno_skriptem": "build_data.py", "seed": 20261119, "dokumenty": [{k: d[k] for k in ("doc_id", "title", "lang", "topic", "revision", "url", "licence")} | {"split": split_of(d["doc_id"])} for d in docs],
       "pocty": {sp: {"celkem": sum(1 for r in allr if r["split"] == sp), "rucni": sum(1 for r in allr if r["split"] == sp and not r["synthetic"]), "synteticke": sum(1 for r in allr if r["split"] == sp and r["synthetic"]),
                      "podle_stitku": {l: sum(1 for r in allr if r["split"] == sp and r["label"] == l) for l in (S, C, N)},
                      "podle_jazyka": {lg: sum(1 for r in allr if r["split"] == sp and r["passage_lang"] == lg) for lg in ("cs", "en")}} for sp in ("train", "val", "final")},
       "lidsky_zkontrolovano": sum(1 for r in allr if r["status"] == "zkontrolovano"),
       "poznamka": "Plán pilotu 600/120/180 – skutečně získáno viz počty. Žádný řádek zatím není lidsky zkontrolován → všechna měření jsou PŘEDBĚŽNÁ.",
       "sha256": {fn: hashlib.sha256(open(os.path.join(D, fn), "rb").read()).hexdigest() for fn in ("documents.json", "pairs.jsonl", "final_inputs.jsonl")}}
json.dump(man, open(os.path.join(D, "manifest.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(json.dumps(man["pocty"], ensure_ascii=False))
