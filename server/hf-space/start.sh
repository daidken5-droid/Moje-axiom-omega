#!/bin/sh
# AXIOM Node – OpenAI-kompatibilní API na portu 7860 (https://<uživatel>-<space>.hf.space/v1).
# AXIOM_API_KEY nastav v Space → Settings → Secrets; bez něj by server mohl používat kdokoli.
set -e
mkdir -p "$LLAMA_CACHE"
if [ -z "$AXIOM_API_KEY" ]; then
  echo "VAROVÁNÍ: AXIOM_API_KEY není nastaven – server je veřejný." >&2
fi
exec /app/llama-server \
  -hf "$AXIOM_MODEL" \
  --host 0.0.0.0 --port 7860 \
  --jinja -c "$AXIOM_CTX" -t "$AXIOM_THREADS" \
  --alias axiom \
  ${AXIOM_API_KEY:+--api-key "$AXIOM_API_KEY"}
