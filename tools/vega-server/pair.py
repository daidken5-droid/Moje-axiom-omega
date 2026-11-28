#!/usr/bin/env python3
"""Vypíše párovací kód pro VEGA: VEGA-PAIR:<base64url JSON {"v":1,"u":adresa,"m":model}>.

Klíč v kódu NENÍ – logy Space i terminál může vidět někdo další. Klíč (VEGA_KEY) zadáš ve VEGA
zvlášť, jednou; VEGA ho uloží šifrovaně a pošle jen na tuhle adresu.
Použití: python3 pair.py https://jmeno-vega.hf.space/v1 vega-qwen3-4b
"""
import base64, json, sys


def code(url: str, model: str) -> str:
    raw = json.dumps({"v": 1, "u": url, "m": model}, separators=(",", ":"), ensure_ascii=False).encode()
    return "VEGA-PAIR:" + base64.urlsafe_b64encode(raw).decode().rstrip("=")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    c = code(sys.argv[1], sys.argv[2])
    line = "=" * 64
    print(f"{line}\nPÁROVACÍ KÓD PRO VEGA (Nastavení → Vlastní model → Párovací kód):\n\n{c}\n\n"
          f"Adresa: {sys.argv[1]}\nModel:  {sys.argv[2]}\nKlíč:   tvůj VEGA_KEY (zadej ve VEGA zvlášť)\n{line}", flush=True)
