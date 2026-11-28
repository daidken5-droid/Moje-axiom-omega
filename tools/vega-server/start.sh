#!/bin/sh
# Spustí llama-server s povinným klíčem a vypíše párovací kód pro VEGA.
set -eu
if [ -z "${VEGA_KEY:-}" ]; then
  echo "CHYBA: chybí VEGA_KEY (tajný klíč serveru)." >&2
  echo "  Hugging Face: Settings → Variables and secrets → New secret → VEGA_KEY" >&2
  echo "  PC: soubor .env vedle docker-compose.yml (VEGA_KEY=...)" >&2
  exit 1
fi
if [ "${#VEGA_KEY}" -lt 24 ]; then echo "CHYBA: VEGA_KEY musí mít aspoň 24 znaků (náhodných)." >&2; exit 1; fi

# veřejná adresa: Hugging Face nastavuje SPACE_HOST, na PC ji zadáš ve VEGA_PUBLIC_URL (např. http://192.168.1.20:7860)
if [ -n "${SPACE_HOST:-}" ]; then PUBLIC="https://${SPACE_HOST}/v1"; else PUBLIC="${VEGA_PUBLIC_URL:-http://IP-TVEHO-PC:${VEGA_PORT}}/v1"; fi
PUBLIC=$(printf '%s' "$PUBLIC" | sed 's#/v1/v1$#/v1#')

if command -v python3 >/dev/null 2>&1; then
  python3 /vega/pair.py "$PUBLIC" "$VEGA_ALIAS" || true
else
  echo "Párování ručně: adresa $PUBLIC · model $VEGA_ALIAS · klíč = tvůj VEGA_KEY"
fi

exec /app/llama-server \
  -hf "$VEGA_MODEL_HF" \
  --alias "$VEGA_ALIAS" \
  --host 0.0.0.0 --port "$VEGA_PORT" \
  --api-key "$VEGA_KEY" \
  --ctx-size "$VEGA_CTX" \
  --jinja \
  --parallel 1 \
  --no-webui
