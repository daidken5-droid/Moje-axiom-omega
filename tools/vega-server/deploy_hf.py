#!/usr/bin/env python3
"""Nasadí VEGA server do Hugging Face Space – spouštíš u sebe, ne ve VEGA.

  pip install huggingface_hub
  HF_TOKEN=hf_... python3 deploy_hf.py jmeno-space            # Space <tvůj účet>/jmeno-space
  HF_TOKEN=hf_... python3 deploy_hf.py jmeno-space --new-key  # vygeneruje a nastaví nový VEGA_KEY

Token (HF_TOKEN, oprávnění Write) se nikam neukládá. VEGA_KEY se vypíše JEDNOU – vlož ho do VEGA.
Space je Public (VEGA by se k Private nedostala), bez VEGA_KEY ale server nikomu neodpoví.
"""
import argparse, os, secrets, sys
from pathlib import Path

FILES = ["Dockerfile", "start.sh", "pair.py", "README.md"]


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("space", help="název Space (bez účtu), např. vega-server")
    ap.add_argument("--new-key", action="store_true", help="vygenerovat a nastavit nový VEGA_KEY (jinak se ponechá stávající)")
    ap.add_argument("--dry-run", action="store_true", help="jen vypsat, co by se stalo")
    a = ap.parse_args(argv)
    here = Path(__file__).resolve().parent
    missing = [f for f in FILES if not (here / f).is_file()]
    if missing:
        sys.exit(f"Chybí soubory: {missing}")
    token = os.environ.get("HF_TOKEN", "")
    if not a.dry_run and not token.startswith("hf_"):
        sys.exit("Nastav HF_TOKEN (huggingface.co → Settings → Access Tokens → Write).")
    key = secrets.token_urlsafe(32) if a.new_key else None
    if a.dry_run:
        print(f"[nanečisto] Space <účet>/{a.space} (Docker, CPU basic, Public), nahrát {FILES}" + (", nastavit nový VEGA_KEY" if key else ""))
        return 0
    from huggingface_hub import HfApi
    api = HfApi(token=token)
    user = api.whoami()["name"]
    repo = f"{user}/{a.space}"
    existed = api.repo_exists(repo, repo_type="space")
    api.create_repo(repo, repo_type="space", space_sdk="docker", private=False, exist_ok=True)
    if not existed and not key:
        key = secrets.token_urlsafe(32)  # nový Space bez VEGA_KEY by nenastartoval
    if key:
        api.add_space_secret(repo, "VEGA_KEY", key)
    api.upload_folder(repo_id=repo, repo_type="space", folder_path=str(here), allow_patterns=FILES, commit_message="VEGA server")
    url = f"https://{user.lower().replace('_', '-').replace('.', '-')}-{a.space.lower().replace('_', '-').replace('.', '-')}.hf.space/v1"
    print(f"Hotovo: https://huggingface.co/spaces/{repo}\nAdresa pro VEGA: {url}")
    print("Párovací kód najdeš v Logs Space po startu (1–3 min)." + (f"\n\nVEGA_KEY (vlož do VEGA → Klíč serveru, ukazuji jen teď):\n{key}" if key else
          "\nVEGA_KEY zůstal beze změny (nový: --new-key)."))
    return 0


if __name__ == "__main__":
    sys.exit(main())
