#!/usr/bin/env bash
# Stáhne PŘIPNUTÉ zdroje CLDR pro compile_cldr_iso.py. Použití: tools/factc/fetch_cldr.sh <cílový_adresář> [commit]
set -euo pipefail
D="$1"; SHA="${2:-7d2b9cb11cec08f4d333ad73209deb3df0711c3b}"; mkdir -p "$D"; echo "$SHA" > "$D/cldr.sha"
B="https://raw.githubusercontent.com/unicode-org/cldr-json/$SHA"
curl -fsS -o "$D/codeMappings.json"   "$B/cldr-json/cldr-core/supplemental/codeMappings.json"
curl -fsS -o "$D/currencyData.json"   "$B/cldr-json/cldr-core/supplemental/currencyData.json"
curl -fsS -o "$D/territoryInfo.json"  "$B/cldr-json/cldr-core/supplemental/territoryInfo.json"
curl -fsS -o "$D/cs_territories.json" "$B/cldr-json/cldr-localenames-full/main/cs/territories.json"
curl -fsS -o "$D/currencies.json"     "$B/cldr-json/cldr-numbers-full/main/cs/currencies.json"
curl -fsS -o "$D/LICENSE"             "$B/LICENSE"
sha256sum "$D"/*.json "$D/LICENSE"
