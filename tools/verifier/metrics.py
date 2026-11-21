"""Metriky po jazykových kombinacích: matice záměn, falešné podpory (čitatel/jmenovatel), precision/recall PODPORUJE,
provozní selhání (NEVYHODNOCENO: chyba nebo zkrácená pasáž), práh podpory a bootstrap po dokumentech."""
import numpy as np, random
from common import LABELS, combo
S = 0
def decide(p, t):
    """PODPORUJE jen když p(podpora) ≥ t; jinak silnější ze zbylých tříd."""
    return S if p[S] >= t else (1 if p[1] >= p[2] else 2)

def report(rows, P, T, t, fail_on_trunc=True):
    out = {}
    for c in sorted(set(combo(r) for r in rows)) + ["vše"]:
        idx = [i for i, r in enumerate(rows) if c == "vše" or combo(r) == c]
        cm = [[0] * 3 for _ in range(3)]; fails = 0; fs = 0; neg = 0; tp = 0; pp = 0; pos = 0
        for i in idx:
            g = LABELS.index(rows[i]["label"])
            if fail_on_trunc and T[i]: fails += 1; continue      # NEVYHODNOCENO se měří zvlášť, do matice nejde
            y = decide(P[i], t); cm[g][y] += 1
            if g != S: neg += 1; fs += (y == S)
            if g == S: pos += 1; tp += (y == S)
            pp += (y == S)
        out[c] = {"n": len(idx), "matice_zamen (řádek=pravda, sloupec=předpověď; P/N/O)": cm,
                  "falesne_podpory": f"{fs}/{neg}", "precision_PODPORUJE": f"{tp}/{pp}", "recall_PODPORUJE": f"{tp}/{pos}", "NEVYHODNOCENO": f"{fails}/{len(idx)}",
                  "_fs": (fs, neg), "_rec": (tp, pos)}
    return out

def pick_threshold(rows, P, T):
    """Na VALIDACI: nejnižší práh s precision PODPORUJE ≥ 0,9, jinak max F1 (falešná podpora je dražší než opomenutí)."""
    best = None
    for t in np.arange(0.30, 0.991, 0.01):
        r = report(rows, P, T, t)["vše"]; tp, pp = map(int, r["precision_PODPORUJE"].split("/")); _, pos = r["_rec"]
        prec = tp / pp if pp else 1.0; rec = tp / pos if pos else 0.0; f1 = 2 * prec * rec / (prec + rec) if prec + rec else 0
        if prec >= 0.9 and best is None: best = ("precision≥0.9", float(round(t, 2)))
    if best: return best
    ts = max(np.arange(0.30, 0.991, 0.01), key=lambda t: (lambda r: (lambda tp, pp, pos: (2 * (tp / pp if pp else 1) * (tp / pos if pos else 0) / (((tp / pp) if pp else 1) + ((tp / pos) if pos else 0) or 1)))(*map(int, r["precision_PODPORUJE"].split("/")), r["_rec"][1]))(report(rows, P, T, t)["vše"]))
    return ("max F1", float(round(ts, 2)))

def bootstrap_diff(rows, Pa, Ta, ta, Pb, Tb, tb, n=2000, seed=20261119):
    """Rozdíl B − A (míra falešných podpor, recall) s 95% intervalem; vzorkuje se po DOKUMENTECH."""
    rnd = random.Random(seed); docs = sorted(set(r["doc_id"] for r in rows)); res = {}
    for c in sorted(set(combo(r) for r in rows)):
        dfs, drc = [], []
        for _ in range(n):
            pick = [rnd.choice(docs) for _ in docs]; idx = [i for d in pick for i, r in enumerate(rows) if r["doc_id"] == d and combo(r) == c]
            if not idx: continue
            sub = [rows[i] for i in idx]
            ra = report(sub, Pa[idx], [Ta[i] for i in idx], ta)["vše"]; rb = report(sub, Pb[idx], [Tb[i] for i in idx], tb)["vše"]
            def rate(x): a, b = x; return a / b if b else float("nan")
            dfs.append(rate(rb["_fs"]) - rate(ra["_fs"])); drc.append(rate(rb["_rec"]) - rate(ra["_rec"]))
        q = lambda v: [float(np.nanpercentile(v, 2.5)), float(np.nanpercentile(v, 97.5))]
        res[c] = {"rozdil_falesnych_podpor_95CI": q(dfs), "rozdil_recall_95CI": q(drc)}
    return res
