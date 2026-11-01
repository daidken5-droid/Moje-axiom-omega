#!/usr/bin/env python3
"""Přepočítá velikosti, SHA-256 a počty sekcí v packs/catalog.json po úpravě balíčků."""
import hashlib
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "packs")
cat_path = root / "catalog.json"
cat = json.loads(cat_path.read_text(encoding="utf-8"))
for p in cat["packs"]:
    data = (root / p["file"]).read_bytes()
    new_sha = hashlib.sha256(data).hexdigest()
    if new_sha != p["sha256"]:
        p["version"] = int(p.get("version", 1)) + 1
    p["bytes"] = len(data)
    p["sha256"] = new_sha
    p["sections"] = data.decode("utf-8").count("\n## ")
cat_path.write_text(json.dumps(cat, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"{len(cat['packs'])} balíčků aktualizováno")
