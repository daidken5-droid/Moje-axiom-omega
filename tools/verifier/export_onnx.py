"""Export do mobilního balíčku VEGA (ONNX Runtime): fp32 → int8 (dynamická kvantizace), kontrola shody na STEJNÝCH
dvojicích před exportem, po exportu a po kvantizaci, slovník pro tokenizer v aplikaci, testovací vektory, manifest.
  python export_onnx.py --model <cesta k HF modelu> --name referencni --threshold 0.91 --out bundles/referencni"""
import argparse, json, os, shutil, time, numpy as np, torch
from transformers import AutoTokenizer, AutoModelForSequenceClassification
import subprocess, sys
from common import base_dir, data, load_rows, H, LABELS, MAX_LEN, BASE_REPO, BASE_REV, sha256, encode, combo
from model_io import OnnxPredictor
from metrics import decide
ap = argparse.ArgumentParser(); ap.add_argument("--model", default=None); ap.add_argument("--name", required=True); ap.add_argument("--threshold", type=float, required=True); ap.add_argument("--out", required=True)
a = ap.parse_args(); src = a.model or base_dir(); os.makedirs(a.out, exist_ok=True)
tok = AutoTokenizer.from_pretrained(src); m = AutoModelForSequenceClassification.from_pretrained(src).eval()
fp32 = os.path.join(a.out, "model.fp32.onnx"); int8 = os.path.join(a.out, "model.onnx")
enc, _ = encode(tok, data("val")[:2])
torch.onnx.export(m, (enc["input_ids"], enc["attention_mask"]), fp32, input_names=["input_ids", "attention_mask"], output_names=["logits"],
                  dynamic_axes={"input_ids": {0: "b", 1: "t"}, "attention_mask": {0: "b", 1: "t"}, "logits": {0: "b"}}, opset_version=17)
# kvantizace JEN tabulky embeddingů (int8 po řádcích); dynamická kvantizace aktivací shodu rozbíjela (22/59), viz PROTOKOL.md
subprocess.run([sys.executable, os.path.join(H, "quantize_weights.py"), fp32, int8, "--emb-only"], check=True)
# shoda na stejných dvojicích (validace + vstupy závěrečné sady bez klíče)
rows = data("val") + load_rows(os.path.join(H, "data", "final_inputs.jsonl"))
with torch.no_grad():
    Pt = []
    for r in rows: e, _ = encode(tok, [r]); Pt.append(torch.softmax(m(**e).logits, -1)[0].numpy())
Pt = np.array(Pt)
res = {}
for nm, path in (("fp32", fp32), ("int8", int8)):
    p = OnnxPredictor(path, src); t0 = time.time(); Po, _ = p.predict(rows); dt = (time.time() - t0) / len(rows) * 1000
    agree = sum(decide(x, a.threshold) == decide(y, a.threshold) for x, y in zip(Pt, Po))
    res[nm] = {"max_rozdil_pravdepodobnosti": float(np.abs(Pt - Po).max()), "shoda_rozhodnuti": f"{agree}/{len(rows)}", "ms_na_dvojici_hostitel_CPU": round(dt, 1), "velikost_MB": round(os.path.getsize(path) / 1e6, 1)}
print(json.dumps(res, indent=1, ensure_ascii=False))
# slovník pro tokenizer v aplikaci (kus \t skóre), pořadí = id
tj = json.load(open(os.path.join(src, "tokenizer.json"), encoding="utf-8"))
with open(os.path.join(a.out, "vocab.tsv"), "w", encoding="utf-8") as f:
    for piece, score in tj["model"]["vocab"]: f.write(piece.replace("\t", " ").replace("\n", " ") + "\t" + repr(float(score)) + "\n")
# testovací vektory: přesné id tokenů z originálního tokenizeru (aplikace je musí reprodukovat)
vec = [{"a": r["passage"], "b": r["claim"], "ids": tok(r["passage"], r["claim"], truncation="only_first", max_length=MAX_LEN)["input_ids"]} for r in rows[:40]]
json.dump(vec, open(os.path.join(a.out, "token_vectors.json"), "w", encoding="utf-8"), ensure_ascii=False)
os.remove(fp32)
man = {"format": "vega-verifier/1", "name": a.name, "base_repo": BASE_REPO, "base_rev": BASE_REV, "source": src if a.model else f"{BASE_REPO}@{BASE_REV}",
       "labels": LABELS, "max_len": MAX_LEN, "threshold": a.threshold, "tokenizer": "xlmr-unigram (NFKC, Metaspace ▁, <s> A </s></s> B </s>)", "special": {"bos": 0, "pad": 1, "eos": 2, "unk": 3},
       "quantization": "jen váhy: tabulka embeddingů int8 po řádcích (quantize_weights.py --emb-only), aktivace fp32", "export_parity": res,
       "files": {f: sha256(os.path.join(a.out, f)) for f in ("model.onnx", "vocab.tsv")}, "sizes_MB": {f: round(os.path.getsize(os.path.join(a.out, f)) / 1e6, 1) for f in ("model.onnx", "vocab.tsv")}}
json.dump(man, open(os.path.join(a.out, "manifest.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print("balíček:", a.out, man["sizes_MB"])
