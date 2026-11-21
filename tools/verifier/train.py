"""Dotrénování z referenčního NLI modelu: třícestná hlava + horní vrstvy encoderu (spodní vrstvy a embeddingy zmrazené).
Kontrolní bod po každé epoše (váhy + optimalizátor + epocha); --resume pokračuje. Výběr prahu jen na validaci.
  python train.py --out runs/pilot --epochs 5            # trénink
  python train.py --out runs/pilot --epochs 8 --resume    # pokračování z posledního bodu"""
import argparse, json, os, time, torch, random
from transformers import AutoTokenizer, AutoModelForSequenceClassification
from common import base_dir, data, encode, LABELS, seed_all, SEED, BASE_REPO, BASE_REV
ap = argparse.ArgumentParser(); ap.add_argument("--out", required=True); ap.add_argument("--epochs", type=int, default=5); ap.add_argument("--lr", type=float, default=3e-5)
ap.add_argument("--bs", type=int, default=8); ap.add_argument("--train-layers", type=int, default=2); ap.add_argument("--resume", action="store_true"); ap.add_argument("--max-steps", type=int, default=0)
a = ap.parse_args(); seed_all(); os.makedirs(a.out, exist_ok=True)
dev = "cuda" if torch.cuda.is_available() else "cpu"
b = base_dir(); tok = AutoTokenizer.from_pretrained(b)
ck = sorted([d for d in os.listdir(a.out) if d.startswith("ckpt-")], key=lambda d: int(d.split("-")[1])) if a.resume else []
src = os.path.join(a.out, ck[-1]) if ck else b
m = AutoModelForSequenceClassification.from_pretrained(src).to(dev)
L = m.config.num_hidden_layers
for n, p in m.named_parameters():
    p.requires_grad = n.startswith("classifier") or any(n.startswith(f"roberta.encoder.layer.{i}.") for i in range(L - a.train_layers, L))
opt = torch.optim.AdamW([p for p in m.parameters() if p.requires_grad], lr=a.lr, weight_decay=0.01)
start = 0
if ck: st = torch.load(os.path.join(src, "train_state.pt"), map_location=dev); opt.load_state_dict(st["opt"]); start = st["epoch"]; random.setstate(st["py_rng"]); torch.set_rng_state(st["torch_rng"])
rows = data("train"); print(f"trénink: {len(rows)} dvojic, trénovatelných parametrů {sum(p.numel() for p in m.parameters() if p.requires_grad):,}, zařízení {dev}, start epocha {start}")
log = []; steps = 0; t0 = time.time()
for ep in range(start, a.epochs):
    m.train(); random.shuffle(rows); tot = 0.0
    for i in range(0, len(rows), a.bs):
        bt = rows[i:i + a.bs]; enc, _ = encode(tok, bt); enc = {k: v.to(dev) for k, v in enc.items()}
        y = torch.tensor([LABELS.index(r["label"]) for r in bt], device=dev)
        loss = torch.nn.functional.cross_entropy(m(**enc).logits, y); loss.backward(); opt.step(); opt.zero_grad(); tot += loss.item() * len(bt); steps += 1
        if a.max_steps and steps >= a.max_steps: break
    d = os.path.join(a.out, f"ckpt-{ep + 1}"); m.save_pretrained(d); tok.save_pretrained(d)
    torch.save({"opt": opt.state_dict(), "epoch": ep + 1, "py_rng": random.getstate(), "torch_rng": torch.get_rng_state()}, os.path.join(d, "train_state.pt"))
    log.append({"epoch": ep + 1, "loss": tot / len(rows), "s": round(time.time() - t0, 1)}); print(log[-1])
    if a.max_steps and steps >= a.max_steps: break
json.dump({"base_repo": BASE_REPO, "base_rev": BASE_REV,
           "seed": SEED, "args": vars(a), "log": log, "labels": LABELS}, open(os.path.join(a.out, "train_log.json"), "w"), indent=1, ensure_ascii=False)
