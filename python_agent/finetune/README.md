# Qwen2.5 LoRA Fine-Tuning

This directory contains the practical scaffold for fine-tuning the local auxiliary model used by the Python agent.

## Scope

Do not start by fine-tuning the final policy-answer model. The highest-value narrow tasks in this project are:

- intent routing
- query rewriting
- policy question classification
- policy evidence judgment
- short follow-up completion

These tasks map directly to the existing Python and Java agent architecture, and they are small enough to train efficiently with LoRA / QLoRA.

## Recommended training route

1. Local development:
   - prepare JSONL datasets
   - run small smoke tests on a laptop
   - validate prompt / response format

2. Cloud training:
   - use `Qwen/Qwen2.5-7B-Instruct` with QLoRA on a 24GB or 48GB GPU
   - or start with `Qwen/Qwen2.5-1.5B-Instruct` for cheaper and faster iteration

3. Export:
   - save LoRA adapter
   - optionally merge and export GGUF
   - create an Ollama model via `Modelfile`

4. Inference:
   - route lightweight tasks to Ollama
   - keep complex planning / answer generation on the stronger cloud model if needed

## Directory layout

- `datasets/`: JSONL training data
- `scripts/train_unsloth.py`: Unsloth training entry
- `modelfiles/qwen2.5-agent-aux.Modelfile`: Ollama model template
- `runpod/`: cloud bootstrap notes

## Dataset format

Use chat-style JSONL. Each line should look like:

```json
{
  "task": "intent_router",
  "messages": [
    {"role": "system", "content": "你是一个铁路客服多专家路由评分器..."},
    {"role": "user", "content": "【当前问题】帮我买明天北京到上海的票"},
    {"role": "assistant", "content": "{\"actionScore\":0.92,\"ticketScore\":0.18,\"policyScore\":0.01,\"chitchatScore\":0.0,\"recommended\":\"ACTION\",\"confidence\":0.92,\"reason\":\"购票动作请求\"}"}
  ]
}
```

Keep outputs deterministic and schema-stable. This is more important than style richness for these tasks.

## Suggested first datasets

Build separate files or a mixed file with `task` labels for:

- `intent_router`
- `query_refiner`
- `policy_classifier`
- `policy_evidence_judge`

Aim for:

- 300 to 800 samples per task for a first useful version
- at least 10% validation split
- real project prompts and outputs, not generic synthetic fluff

## Cloud recommendation

- cost-sensitive: Runpod `RTX 4090 24GB`
- more headroom: Runpod / Lambda `A6000 48GB`
- training mode: `4-bit QLoRA`

## Ollama deployment

After training:

1. export adapter or merged model
2. update the Modelfile path
3. run:

```bash
ollama create qwen2.5-agent-aux -f python_agent/finetune/modelfiles/qwen2.5-agent-aux.Modelfile
ollama run qwen2.5-agent-aux
```

## Integration point in this repo

The Python agent already routes lightweight tasks through `generate_fast_text(...)`.
When `OLLAMA_ENABLED=true`, the following paths can use the local auxiliary model first:

- `QueryRefiner`
- `ContextDependencyClassifier`
- `IntentScorer`
- `PolicyRouter`
- `PolicyComplexityClassifier`
