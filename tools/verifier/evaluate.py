"""Vyhodnocení: výchozí referenční NLI × kandidát. Práh se vybírá JEN na validaci; --final čte uzamčený klíč.
  python evaluate.py --model runs/pilot/ckpt-5 [--final]"""
import argparse, json, os
import numpy as np
from common import base_dir, data, final_with_key
from model_io import TorchPredictor
from metrics import report, pick_threshold, bootstrap_diff
ap = argparse.ArgumentParser(); ap.add_argument("--model", default=None); ap.add_argument("--final", action="store_true"); ap.add_argument("--out", default="eval.json")
a = ap.parse_args()
val = data("val"); res = {"pozor": "Štítky jsou NÁVRHY modelu bez lidské kontroly → měření PŘEDBĚŽNÉ."}
models = {"referencni_NLI": base_dir()} | ({"kandidat": a.model} if a.model else {})
pred = {}
for name, path in models.items():
    p = TorchPredictor(path); Pv, Tv = p.predict(val); how, t = pick_threshold(val, Pv, Tv)
    res[name] = {"prah": t, "jak": how, "validace": {k: {kk: vv for kk, vv in v.items() if not kk.startswith("_")} for k, v in report(val, Pv, Tv, t).items()}}
    if a.final:
        fin = final_with_key(); Pf, Tf = p.predict(fin); pred[name] = (Pf, Tf, t)
        res[name]["zaverecna"] = {k: {kk: vv for kk, vv in v.items() if not kk.startswith("_")} for k, v in report(fin, Pf, Tf, t).items()}
if a.final and "kandidat" in pred:
    fin = final_with_key(); A, B = pred["referencni_NLI"], pred["kandidat"]
    res["rozdil_kandidat_minus_referencni"] = bootstrap_diff(fin, A[0], A[1], A[2], B[0], B[1], B[2])
json.dump(res, open(a.out, "w"), indent=1, ensure_ascii=False); print(json.dumps(res, indent=1, ensure_ascii=False)[:6000])
