#!/usr/bin/env python3
"""
Sestaví předpřipravenou offline knihovnu AVIOR (SQLite FTS4) z Wikimedia dat na Hugging Face (Wikipedie, Wikizdroje).

Výstup má PŘESNĚ schéma, které čte aplikace (app/.../data/LibraryDb.kt):
  progress(lib, offset, total, done, updated)
  articles = fts4(title, body, lib, url, notindexed=lib, notindexed=url, prefix="5", tokenize=unicode61)

Použití:
  python3 tools/build_library.py --lib wiki-cs --parquet-dir ./parquet --out wiki-cs.db [--limit N]
Licence dat: Wikimedia CC BY-SA 4.0 (uveď zdroj při dalším šíření).
"""
import argparse
import glob
import os
import sqlite3
import sys
import time


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--lib", required=True)
    ap.add_argument("--parquet-dir", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--limit", type=int, default=0, help="jen pro testy: max. počet článků")
    ap.add_argument("--min-chars", type=int, default=80)
    a = ap.parse_args()

    import pyarrow.parquet as pq  # noqa: import až po argumentech (rychlá nápověda bez pyarrow)

    files = sorted(glob.glob(os.path.join(a.parquet_dir, "*.parquet")))
    if not files:
        print("Žádné .parquet soubory", file=sys.stderr)
        return 1
    if os.path.exists(a.out):
        os.remove(a.out)

    db = sqlite3.connect(a.out)
    db.execute("PRAGMA journal_mode=OFF")
    db.execute("PRAGMA synchronous=OFF")
    db.execute("PRAGMA page_size=4096")
    db.execute(
        "CREATE TABLE progress(lib TEXT PRIMARY KEY, offset INTEGER NOT NULL, total INTEGER NOT NULL, "
        "done INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL)"
    )
    db.execute(
        'CREATE VIRTUAL TABLE articles USING fts4(title, body, lib, url, notindexed=lib, notindexed=url, '
        'prefix="5", tokenize=unicode61)'
    )
    # Android SQLiteOpenHelper verze (LibraryDb = 1)
    db.execute("PRAGMA user_version=1")

    total = 0
    kept = 0
    t0 = time.time()
    for f in files:
        pf = pq.ParquetFile(f)
        for batch in pf.iter_batches(batch_size=2000, columns=["title", "text", "url"]):
            rows = []
            titles = batch.column("title").to_pylist()
            texts = batch.column("text").to_pylist()
            urls = batch.column("url").to_pylist()
            for title, text, url in zip(titles, texts, urls):
                total += 1
                if not title or not text:
                    continue
                text = text.strip()
                if len(text) < a.min_chars:
                    continue
                rows.append((title, text, a.lib, url or ""))
            db.executemany("INSERT INTO articles(title, body, lib, url) VALUES (?,?,?,?)", rows)
            db.commit()
            kept += len(rows)
            if a.limit and kept >= a.limit:
                break
            if total % 50000 < 2000:
                print(f"{total:>8} řádků · {kept:>8} článků · {time.time() - t0:.0f} s", flush=True)
        if a.limit and kept >= a.limit:
            break

    print("Optimalizuji fulltextový index…", flush=True)
    db.execute("INSERT INTO articles(articles) VALUES('optimize')")
    db.execute(
        "INSERT INTO progress(lib, offset, total, done, updated) VALUES (?,?,?,1,?)",
        (a.lib, total, total, int(time.time() * 1000)),
    )
    db.commit()
    db.execute("VACUUM")
    db.close()
    size = os.path.getsize(a.out)
    print(f"Hotovo: {kept} článků z {total} řádků, {size / 1e9:.2f} GB, {time.time() - t0:.0f} s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
