# AXIOM Node pro Hugging Face Spaces (Docker, free CPU basic: 2 vCPU, 16 GB RAM).
# Oficiální obraz llama.cpp serveru; model se stáhne z Hugging Face při startu.
FROM ghcr.io/ggml-org/llama.cpp:server

ENV LLAMA_CACHE=/tmp/llama-cache \
    AXIOM_MODEL=unsloth/gemma-4-E4B-it-GGUF:Q4_K_M \
    AXIOM_CTX=8192 \
    AXIOM_THREADS=2

COPY start.sh /start.sh
RUN chmod +x /start.sh

EXPOSE 7860
ENTRYPOINT ["/start.sh"]
