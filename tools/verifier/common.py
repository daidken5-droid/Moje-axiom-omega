"""Společné: data, model, třídy. Pořadí tříd = pořadí výstupů modelu (entailment, neutral, contradiction)."""
import json, os, random, csv, hashlib, urllib.request
import numpy as np
H = os.path.dirname(os.path.abspath(__file__))
LABELS = ["PODPORUJE", "NEDOSTAČUJE", "ODPORUJE"]          # = id2label 0 entailment, 1 neutral, 2 contradiction
BASE_REPO = "MoritzLaurer/multilingual-MiniLMv2-L6-mnli-xnli"
BASE_REV = "0a71e92a985b6e1ad1828cf67ce9c459639c1dca"      # přesná revize vah (licence MIT)
MAX_LEN = 256
SEED = 20261119
FILES = ["config.json", "model.safetensors", "tokenizer.json", "tokenizer_config.json", "special_tokens_map.json"]

def seed_all(s=SEED):
    import torch; random.seed(s); np.random.seed(s); torch.manual_seed(s)

def base_dir(cache=None):
    """Stáhne přesnou revizi základních vah (jednou) – bez účtu, veřejné soubory Hugging Face."""
    d = cache or os.environ.get("VERIFIER_BASE", os.path.join(H, "cache", "base-" + BASE_REV[:8]))
    os.makedirs(d, exist_ok=True)
    for f in FILES:
        p = os.path.join(d, f)
        if not os.path.exists(p):
            urllib.request.urlretrieve(f"https://huggingface.co/{BASE_REPO}/resolve/{BASE_REV}/{f}", p)
    return d

def load_rows(path):
    return [json.loads(l) for l in open(path, encoding="utf-8") if l.strip()]

def data(split):
    rows = load_rows(os.path.join(H, "data", "pairs.jsonl"))
    return [r for r in rows if r["split"] == split and r["status"] != "vyrazeno"]

def final_with_key():
    """JEN pro závěrečné vyhodnocení (evaluate.py --final). Trénink ani výběr prahu tuto funkci nevolají."""
    key = {r["id"]: r["label"] for r in csv.DictReader(open(os.path.join(H, "locked", "final_key.csv"), encoding="utf-8"))}
    rows = load_rows(os.path.join(H, "data", "final_inputs.jsonl"))
    return [dict(r, label=key[r["id"]]) for r in rows if r["id"] in key]

def combo(r): return f"{r['passage_lang']}→cs"

def encode(tok, rows, max_len=MAX_LEN):
    """Pasáž = premisa, tvrzení = hypotéza; zkracuje se jen pasáž. truncated = pasáž se nevešla celá."""
    enc = tok([r["passage"] for r in rows], [r["claim"] for r in rows], truncation="only_first", max_length=max_len, padding=True, return_tensors="pt", return_overflowing_tokens=False)
    full = tok([r["passage"] for r in rows], [r["claim"] for r in rows], truncation=False)
    trunc = [len(x) > max_len for x in full["input_ids"]]
    return enc, trunc

def sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""): h.update(b)
    return h.hexdigest()
