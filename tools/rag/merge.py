#!/usr/bin/env python3
"""Sloučí výstupy avior_embed do jednoho indexu AVRAG1 (čte ho aplikace: core/.../rag/RagIndex.kt).

Formát (little-endian):
  8 B  magic  b"AVRAG1\\0\\0"
  4 B  int32  dim
  4 B  int32  count
  count × int32   rowid článku (seřazeno vzestupně)
  count × float32 měřítko int8 vektoru
  count × dim × int8 vektory (L2-normalizované, Matryoshka zkrácené na dim)
"""
import argparse, glob, struct, sys
import numpy as np

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dim", type=int, default=256)
    ap.add_argument("--out", required=True)
    ap.add_argument("inputs", nargs="+")
    a = ap.parse_args()
    rec = np.dtype([("id", "<i4"), ("scale", "<f4"), ("v", "i1", (a.dim,))])
    parts = []
    for p in sorted(sum((glob.glob(x) for x in a.inputs), [])):
        arr = np.fromfile(p, dtype=rec)
        print(p, len(arr))
        parts.append(arr)
    allr = np.concatenate(parts)
    allr = allr[np.argsort(allr["id"], kind="stable")]
    ids = allr["id"]
    if len(ids) > 1 and (np.diff(ids) == 0).any():
        print("duplicitní rowid", file=sys.stderr)
        return 1
    with open(a.out, "wb") as f:
        f.write(b"AVRAG1\0\0")
        f.write(struct.pack("<ii", a.dim, len(allr)))
        f.write(ids.astype("<i4").tobytes())
        f.write(allr["scale"].astype("<f4").tobytes())
        f.write(np.ascontiguousarray(allr["v"]).tobytes())
    print(f"{len(allr)} vektorů → {a.out}")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
