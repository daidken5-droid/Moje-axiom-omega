#!/usr/bin/env python3
"""
Doladění (LoRA) mozku AVIOR na zdarma GPU (Kaggle Notebooks nebo Google Colab, karta T4 16 GB).

Co se učí: tvoje oceněné odpovědi (✓) + sada dovedností AVIORu (finetune/skills.jsonl) –
protokol agenta, výroba APK a dokumentů, výpočty ze STEP-0, styl RANDY, poctivé sebepoznání.
Výsledek: malý adaptér avior-lora.gguf (desítky MB), který se v aplikaci nahraje
v Moduly → Doladění a nasadí nad stejný základní mozek v telefonu.

Postup (Kaggle: Settings → Accelerator: GPU T4; Internet: On):
  !pip install -q unsloth
  !git clone --depth 1 https://github.com/ggml-org/llama.cpp && pip install -q -r llama.cpp/requirements/requirements-convert_lora_to_gguf.txt
  # nahraj avior-trenink.jsonl z telefonu (Paměť → … → Exportovat trénovací data) do /kaggle/working
  !python train_lora.py --data avior-trenink.jsonl --base unsloth/gemma-4-E4B-it

Adaptér MUSÍ odpovídat základnímu mozku v telefonu:
  OMEGA Core  (Gemma 4 E4B)   → --base unsloth/gemma-4-E4B-it
  OMEGA Lite  (Gemma 4 E2B)   → --base unsloth/gemma-4-E2B-it
  Qwen3.5 2B / Qwen3 1.7B     → --base unsloth/Qwen3.5-2B  /  unsloth/Qwen3-1.7B
"""
import argparse
import json
import subprocess
import sys
from pathlib import Path


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True, help="JSONL s {\"messages\": [...]} (export z aplikace)")
    ap.add_argument("--skills", default=str(Path(__file__).with_name("skills.jsonl")), help="sada dovedností AVIORu")
    ap.add_argument("--base", default="unsloth/gemma-4-E4B-it")
    ap.add_argument("--epochs", type=float, default=2)
    ap.add_argument("--rank", type=int, default=16)
    ap.add_argument("--max-len", type=int, default=2048)
    ap.add_argument("--out", default="avior-lora")
    ap.add_argument("--llama-cpp", default="llama.cpp", help="složka s klonem llama.cpp (pro převod do GGUF)")
    a = ap.parse_args()

    from datasets import Dataset
    from trl import SFTConfig, SFTTrainer
    from unsloth import FastLanguageModel

    rows = []
    for f in [a.data, a.skills]:
        p = Path(f)
        if p.exists():
            rows += [json.loads(l) for l in p.read_text(encoding="utf-8").splitlines() if l.strip()]
    if not rows:
        print("Žádná trénovací data.", file=sys.stderr)
        return 1
    print(f"{len(rows)} trénovacích příkladů")

    model, tok = FastLanguageModel.from_pretrained(a.base, max_seq_length=a.max_len, load_in_4bit=True)
    model = FastLanguageModel.get_peft_model(
        model, r=a.rank, lora_alpha=a.rank, lora_dropout=0, bias="none",
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
        use_gradient_checkpointing="unsloth", random_state=42,
    )

    def fmt(r):
        msgs = r["messages"]
        # některé šablony (Gemma) nemají roli system → přilep ji k první otázce
        if msgs and msgs[0]["role"] == "system" and "gemma" in a.base.lower():
            msgs = [{"role": "user", "content": msgs[0]["content"] + "\n\n" + msgs[1]["content"]}] + msgs[2:]
        return {"text": tok.apply_chat_template(msgs, tokenize=False)}

    ds = Dataset.from_list(rows).map(fmt, remove_columns=["messages"])
    trainer = SFTTrainer(
        model=model, tokenizer=tok, train_dataset=ds,
        args=SFTConfig(
            dataset_text_field="text", max_seq_length=a.max_len, per_device_train_batch_size=2,
            gradient_accumulation_steps=4, num_train_epochs=a.epochs, learning_rate=2e-4, warmup_steps=5,
            logging_steps=5, optim="adamw_8bit", weight_decay=0.01, lr_scheduler_type="linear",
            seed=42, output_dir="outputs", report_to="none",
        ),
    )
    trainer.train()
    model.save_pretrained(a.out)
    tok.save_pretrained(a.out)
    print(f"Adaptér PEFT uložen do {a.out}/")

    conv = Path(a.llama_cpp) / "convert_lora_to_gguf.py"
    if not conv.exists():
        print(f"Chybí {conv} – naklonuj llama.cpp a převeď ručně:\n"
              f"  python llama.cpp/convert_lora_to_gguf.py {a.out} --base-model-id {a.base} --outtype f16 --outfile {a.out}.gguf")
        return 0
    subprocess.check_call([sys.executable, str(conv), a.out, "--base-model-id", a.base, "--outtype", "f16", "--outfile", f"{a.out}.gguf"])
    print(f"Hotovo: {a.out}.gguf – nahraj ho v aplikaci: Moduly → Doladění → Nahrát adaptér.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
