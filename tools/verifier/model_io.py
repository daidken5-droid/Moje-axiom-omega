"""Předpověď pro dvojice: HF model (torch) nebo ONNX. Výsledek: pravděpodobnosti v pořadí LABELS + příznak zkrácení."""
import numpy as np, torch
from common import encode, MAX_LEN

class TorchPredictor:
    def __init__(self, path):
        from transformers import AutoTokenizer, AutoModelForSequenceClassification
        self.tok = AutoTokenizer.from_pretrained(path); self.m = AutoModelForSequenceClassification.from_pretrained(path).eval()
    def predict(self, rows, bs=16):
        P, T = [], []
        for i in range(0, len(rows), bs):
            enc, tr = encode(self.tok, rows[i:i + bs])
            with torch.no_grad(): P.append(torch.softmax(self.m(**enc).logits, -1).numpy())
            T += tr
        return np.concatenate(P), T

class OnnxPredictor:
    def __init__(self, onnx_path, tok_path):
        import onnxruntime as ort
        from transformers import AutoTokenizer
        self.s = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"]); self.tok = AutoTokenizer.from_pretrained(tok_path)
        self.names = [i.name for i in self.s.get_inputs()]
    def predict(self, rows):
        P, T = [], []
        for r in rows:
            enc, tr = encode(self.tok, [r])
            feed = {k: enc[k].numpy() for k in self.names}
            lg = self.s.run(None, feed)[0][0]; e = np.exp(lg - lg.max()); P.append(e / e.sum()); T += tr
        return np.array(P), T
