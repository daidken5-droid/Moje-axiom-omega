"""Rychlá zkouška technického běhu na CPU: NÁHODNĚ inicializovaná hlava (jen ověření běhu!), pár kroků učení,
kontrola, že se váhy změnily, uložení, nové načtení a vyhodnocení jedné dvojice."""
import os, sys, tempfile, torch
from transformers import AutoTokenizer, AutoModelForSequenceClassification
from common import base_dir, data, encode, LABELS, seed_all
seed_all()
b = base_dir(); tok = AutoTokenizer.from_pretrained(b)
m = AutoModelForSequenceClassification.from_pretrained(b)
torch.nn.init.normal_(m.classifier.out_proj.weight, std=0.02); torch.nn.init.zeros_(m.classifier.out_proj.bias)   # náhodná hlava
rows = data("train")[:8]; y = torch.tensor([LABELS.index(r["label"]) for r in rows])
before = m.classifier.out_proj.weight.detach().clone()
opt = torch.optim.AdamW([p for p in m.parameters() if p.requires_grad], lr=1e-3); m.train()
for step in range(3):
    enc, _ = encode(tok, rows); loss = torch.nn.functional.cross_entropy(m(**enc).logits, y); loss.backward(); opt.step(); opt.zero_grad()
    print(f"krok {step + 1}: ztráta {loss.item():.4f}")
changed = (m.classifier.out_proj.weight - before).abs().max().item()
assert changed > 0, "váhy se nezměnily"
out = tempfile.mkdtemp(prefix="smoke-"); m.save_pretrained(out); tok.save_pretrained(out)
m2 = AutoModelForSequenceClassification.from_pretrained(out).eval()
assert torch.allclose(m2.classifier.out_proj.weight, m.classifier.out_proj.weight), "znovu načtené váhy nesedí"
enc, _ = encode(tok, rows[:1])
with torch.no_grad(): p = torch.softmax(m2(**enc).logits, -1)[0]
print(f"OK: změna vah {changed:.2e}; uloženo a znovu načteno ({out}); dvojice „{rows[0]['claim'][:50]}…“ → {LABELS[int(p.argmax())]} {p.numpy().round(3)} (hlava náhodná – výsledek nemá význam)")
