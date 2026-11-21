# VEGA – vlastní AI server (llama.cpp, OpenAI API). Funguje jako Docker Space na Hugging Face (zdarma, CPU)
# i na vlastním PC (docker compose up). Model se stáhne při prvním startu, v obrazu není.
# Obraz llama.cpp připnutý na konkrétní sestavení (ne „latest“) – změna verze = vědomé rozhodnutí.
ARG LLAMA_IMAGE=ghcr.io/ggml-org/llama.cpp:server-b12464
FROM ${LLAMA_IMAGE}

ENV VEGA_MODEL_HF="unsloth/Qwen3-4B-Instruct-2507-GGUF:Q4_K_M" \
    VEGA_ALIAS="vega-qwen3-4b" \
    VEGA_CTX="16384" \
    VEGA_PORT="7860" \
    LLAMA_CACHE="/tmp/llama-cache" \
    LD_LIBRARY_PATH="/app"

COPY start.sh /vega/start.sh
COPY pair.py /vega/pair.py
RUN chmod 755 /vega/start.sh
EXPOSE 7860
ENTRYPOINT ["/bin/sh", "/vega/start.sh"]
