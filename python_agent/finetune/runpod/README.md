# Runpod Bootstrap

Recommended target for this project:

- `RTX 4090 24GB` for cost-effective QLoRA
- `RTX A6000 48GB` for more headroom

## Environment bootstrap

```bash
git clone <your-repo>
cd 12306-ticket-service
python -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -r python_agent/finetune/requirements-unsloth.txt
```

## Train

```bash
python python_agent/finetune/scripts/train_unsloth.py \
  --train-file python_agent/finetune/datasets/sample_agent_aux.jsonl \
  --model-name Qwen/Qwen2.5-7B-Instruct \
  --output-dir outputs/qwen2.5-agent-aux-lora \
  --max-seq-length 1024 \
  --batch-size 1 \
  --grad-accum 16 \
  --epochs 2
```

## Export to Ollama

After training:

```bash
ollama create qwen2.5-agent-aux -f python_agent/finetune/modelfiles/qwen2.5-agent-aux.Modelfile
ollama run qwen2.5-agent-aux
```

If you prefer a merged GGUF route, export from your training framework first, then replace the `FROM` / `ADAPTER` lines in the Modelfile accordingly.
