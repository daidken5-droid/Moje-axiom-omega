#!/bin/bash
# AXIOM Node na Oracle Cloud Always Free (Ampere A1: 4 OCPU, 24 GB RAM, Ubuntu 24.04 ARM).
# Spusť na serveru:  curl -fsSL <raw-odkaz-na-tento-soubor> | bash   (nebo ho zkopíruj a spusť)
# Přístup z telefonu doporučujeme přes Tailscale (privátní síť 100.x – AXIOM Ω ji povolí i přes http).
set -euo pipefail

MODEL="${AXIOM_MODEL:-unsloth/Qwen3.6-35B-A3B-GGUF:UD-IQ4_XS}"   # ~17,7 GB, MoE 3B aktivních → rychlý i na CPU
API_KEY="${AXIOM_API_KEY:-$(openssl rand -hex 24)}"

sudo apt-get update -y
sudo apt-get install -y docker.io curl
sudo systemctl enable --now docker

sudo docker rm -f axiom-node 2>/dev/null || true
sudo docker run -d --name axiom-node --restart unless-stopped \
  -p 8080:8080 -v axiom-cache:/root/.cache \
  ghcr.io/ggml-org/llama.cpp:server \
  -hf "$MODEL" --host 0.0.0.0 --port 8080 --jinja -c 16384 -t 4 --alias axiom --api-key "$API_KEY"

if ! command -v tailscale >/dev/null; then
  curl -fsSL https://tailscale.com/install.sh | sh
fi
echo
echo "Hotovo. Teď spusť:  sudo tailscale up   (a přihlas se stejným účtem jako v telefonu)"
echo "V AXIOM Ω → Moduly → AXIOM Node:"
echo "  adresa:  http://$(tailscale ip -4 2>/dev/null | head -1 || echo '<tailscale-ip>'):8080/v1"
echo "  model:   axiom"
echo "  klíč:    $API_KEY"
