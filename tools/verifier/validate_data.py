"""Kontrola dat: schéma, třídy, licence, dělení podle dokumentů a rodin, vyváženost závěrečné sady, skutečné počty."""
import json, os, sys, csv, collections
from common import H, LABELS, load_rows
REQ = ["id", "claim", "passage", "passage_lang", "doc_id", "doc_rev", "char_start", "char_end", "licence", "label", "family", "split", "annotation_origin", "status", "synthetic", "reason"]
rows = load_rows(os.path.join(H, "data", "pairs.jsonl")) + [dict(r, label="?") for r in load_rows(os.path.join(H, "data", "final_inputs.jsonl"))]
docs = {d["doc_id"]: d for d in json.load(open(os.path.join(H, "data", "documents.json"), encoding="utf-8"))}
err = []
for r in rows:
    miss = [k for k in REQ if k not in r and not (k in ("label", "reason") and r["split"] == "final")]
    if miss: err.append(f"{r.get('id')}: chybí {miss}")
    if r["split"] != "final" and r["label"] not in LABELS: err.append(f"{r['id']}: neznámý štítek {r['label']}")
    if not r.get("licence"): err.append(f"{r['id']}: bez licence")
    d = docs.get(r["doc_id"])
    if d and d["text"][r["char_start"]:r["char_end"]] != r["passage"]: err.append(f"{r['id']}: pasáž nesedí s pozicí v dokumentu")
by = collections.defaultdict(set)
for r in rows: by[("doc", r["doc_id"])].add(r["split"]); by[("fam", r["family"])].add(r["split"])
leak = [k for k, v in by.items() if len(v) > 1 and not (k[0] == "doc" and v <= {"train"})]
# syntetické „cizí pasáže“ berou pasáž z jiného TRÉNOVACÍHO dokumentu – únik do val/final je chyba
for k in leak: err.append(f"únik mezi částmi: {k} v {by[k]}")
if any(r["synthetic"] and r["split"] != "train" for r in rows): err.append("syntetické řádky mimo trénink")
key = {r["id"]: r["label"] for r in csv.DictReader(open(os.path.join(H, "locked", "final_key.csv"), encoding="utf-8"))}
fin = [dict(r, label=key.get(r["id"], "?")) for r in rows if r["split"] == "final"]
bal = collections.Counter((r["passage_lang"], r["label"]) for r in fin)
print("závěrečná sada (jazyk pasáže × třída):", dict(sorted(bal.items())))
print("počty:", {sp: sum(1 for r in rows if r["split"] == sp) for sp in ("train", "val", "final")}, "| lidsky zkontrolováno:", sum(1 for r in rows if r["status"] == "zkontrolovano"))
if err: print("CHYBY:\n" + "\n".join(err[:50])); sys.exit(1)
print("data v pořádku")
