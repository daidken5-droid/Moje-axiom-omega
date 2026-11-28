#!/bin/sh
# Vytvoří tools/ui-preview/out/ s UI + mock mostem (bez CSP) pro náhled v prohlížeči.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
UI="$HERE/../../app/src/main/assets/ui"
OUT="$HERE/out"
rm -rf "$OUT" && mkdir -p "$OUT"
cp "$UI"/app.css "$UI"/app.js "$UI"/logo.svg "$HERE"/mock.js "$OUT"/
sed -e '/Content-Security-Policy/d' -e 's#<script src="app.js"></script>#<script src="mock.js"></script><script src="app.js"></script>#' "$UI/index.html" > "$OUT/index.html"
echo "$OUT/index.html"
