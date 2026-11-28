#!/usr/bin/env python3
"""Z předpřipravené knihovny (SQLite FTS4) vytáhne úvody článků a rozdělí je na části pro paralelní výpočet vektorů.

Text pro vektor = název + začátek článku (do ~300 znaků, na hranici slova). Stejný formát čte avior_embed:
řádek "rowid<TAB>text". Rozdělení po souvislých blocích rowid (každá část ~stejně článků).
"""
import argparse, os, re, sqlite3

def lead(title: str, body: str, chars: int) -> str:
    body = re.sub(r"\s+", " ", body or "").strip()
    if len(body) > chars:
        cut = body.rfind(" ", 0, chars)
        body = body[: cut if cut > chars // 2 else chars]
    t = f"{title} — {body}" if body else title
    return t.replace("\t", " ")

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--shards", type=int, default=24)
    ap.add_argument("--chars", type=int, default=300)
    ap.add_argument("--limit", type=int, default=0)
    a = ap.parse_args()
    os.makedirs(a.out_dir, exist_ok=True)
    db = sqlite3.connect(a.db)
    total = db.execute("SELECT count(*) FROM articles").fetchone()[0]
    if a.limit:
        total = min(total, a.limit)
    per = (total + a.shards - 1) // a.shards
    files = [open(os.path.join(a.out_dir, f"shard-{i:02d}.tsv"), "w", encoding="utf-8") for i in range(a.shards)]
    n = 0
    for rowid, title, body in db.execute("SELECT rowid, title, substr(body, 1, ?) FROM articles ORDER BY rowid", (a.chars + 200,)):
        if a.limit and n >= a.limit:
            break
        files[min(n // per, a.shards - 1)].write(f"{rowid}\t{lead(title, body, a.chars)}\n")
        n += 1
    for f in files:
        f.close()
    print(f"{n} článků → {a.shards} částí po ~{per}")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
