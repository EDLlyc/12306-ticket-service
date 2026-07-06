# Python Agent Service

This directory contains the Python migration target for the action-oriented Agent layer. The Java ticket core remains the source of truth for train search, booking, refunding, orders, Redis inventory, RocketMQ, and database transactions.

## Environment

From the repository root:

```bash
source .venv/bin/activate
python -m pip install -r python_agent/requirements.txt
```

Install the heavier RAG/LLM dependencies only when working on retrieval, memory, reranking, or planner migration:

```bash
python -m pip install -r python_agent/requirements-rag.txt
```

Install DeepEval only when running Agent quality evaluation with an LLM judge:

```bash
python -m pip install -r python_agent/requirements-deepeval.txt
```

## Configuration

Copy the example file if you want local overrides:

```bash
cp python_agent/.env.example .env
```

Important settings:

- `JAVA_TICKET_BASE_URL`: existing Spring Boot service, default `http://127.0.0.1:8899`
- `PYTHON_AGENT_PORT`: Python Agent port, default `8898`
- `ZHIPU_API_KEY`: shared with the Java service; when set, Python Agent can use LLM planning, answer summarization, and Milvus dense retrieval embeddings
- `ZHIPU_EMBEDDING_MODEL`: defaults to `embedding-3`
- `ZHIPU_EMBEDDING_DIMENSIONS`: defaults to `1024`, matching the existing `rules_embedding3_1024` Milvus collection
- `ZHIPU_POLICY_FAST_MODEL`: defaults to `glm-4.5-air`, used by lightweight query rewrite and classifier steps
- `OLLAMA_ENABLED`: enable local auxiliary-model inference for lightweight classifier / rewrite tasks
- `OLLAMA_BASE_URL`: defaults to `http://127.0.0.1:11434`
- `OLLAMA_AUX_MODEL`: defaults to `qwen2.5:7b`; this is the local auxiliary model name used for fast structured subtasks
- `PYTHON_AGENT_MILVUS_ENABLED` / `PYTHON_AGENT_OPENSEARCH_ENABLED`: enable live backend retrieval lanes
- `WEATHER_MCP_ENDPOINT`: optional MCP JSON-RPC HTTP endpoint for the external weather tool. When unset or unavailable, travel-advice demos use the built-in deterministic fallback while keeping the same MCP `tools/call` trace shape
- `WEATHER_MCP_TOOL_NAME`: defaults to `get_weather_by_city`
- `WEATHER_MCP_HOST` / `WEATHER_MCP_PORT`: local weather MCP server bind address, default `127.0.0.1:8897`

## Run

```bash
source .venv/bin/activate
uvicorn python_agent.app.main:app --host 0.0.0.0 --port 8898 --reload
```

Or use the repo-aligned startup wrapper, which reuses the same `dev-env.sh` / `ZHIPU_*` environment convention as the Java service:

```bash
./python_agent/dev-start.sh
```

For a persistent background run with logs:

```bash
./python_agent/dev-start.sh --tmux
tail -f .run/python-agent.log
```

## Weather MCP

The repo includes a real local weather MCP JSON-RPC server backed by Open-Meteo:

```bash
source .venv/bin/activate
./python_agent/weather-mcp-start.sh
```

Or run it in tmux:

```bash
./python_agent/weather-mcp-start.sh --tmux
tail -f .run/weather-mcp.log
```

The MCP endpoint is:

```text
http://127.0.0.1:8897/mcp
```

`./python_agent/dev-start.sh` sets `WEATHER_MCP_ENDPOINT` to this local endpoint by default. If the weather MCP server is not running or Open-Meteo is unavailable, the Agent keeps the same MCP trace shape and falls back to deterministic local weather for demos.

## Tests

```bash
source .venv/bin/activate
python -m unittest python_agent.tests.test_action_agent
python -m unittest python_agent.tests.test_weather_mcp
```

## Agent Eval

The repo includes an Agent golden-case eval set:

```text
python_agent/evals/agent_golden_cases.jsonl
```

Run deterministic Agent metrics without external services:

```bash
python -m python_agent.evals.evaluate_deepeval_agent
```

This checks:

- expected goal
- tool selection accuracy
- tool argument accuracy
- required answer fragments
- MCP trace presence for travel-advice cases
- duplicate side-effect protection checks

The report is written to:

```text
.run/deepeval-agent-report.json
```

If `deepeval` and a judge model are configured, enable optional G-Eval scoring:

```bash
python -m python_agent.evals.evaluate_deepeval_agent --deepeval-judge
```

Use strict mode when you want the command to fail if DeepEval or the judge model is not available:

```bash
python -m python_agent.evals.evaluate_deepeval_agent --require-deepeval-judge
```

The LLM judge receives the user question, expected task, actual answer, selected tool, tool arguments, final observation, and a short rubric. It is used as an answer-quality score on top of deterministic checks; it does not replace tool-selection and argument assertions.

DeepEval uses its configured model provider. A common local setup is:

```bash
export OPENAI_API_KEY='your-judge-key'
# optional when using an OpenAI-compatible endpoint:
export OPENAI_BASE_URL='https://your-openai-compatible-endpoint/v1'
```

Use Zhipu as the DeepEval judge through its OpenAI-compatible endpoint:

```bash
export ZHIPU_API_KEY='your-zhipu-key'
export ZHIPU_POLICY_MODEL='glm-4.5-air'
python -m python_agent.evals.evaluate_deepeval_agent \
  --require-deepeval-judge \
  --judge-provider zhipu
```

If your Zhipu-compatible endpoint differs, override it explicitly:

```bash
python -m python_agent.evals.evaluate_deepeval_agent \
  --require-deepeval-judge \
  --judge-provider zhipu \
  --judge-base-url 'https://open.bigmodel.cn/api/paas/v4/' \
  --judge-model 'glm-4.5-air'
```

The DeepEval-compatible test entrypoint is:

```bash
python -m pytest python_agent/deepeval_tests
# or, when using the DeepEval CLI:
deepeval test run python_agent/deepeval_tests
```

## Initial API

Health check:

```bash
curl http://127.0.0.1:8898/health
```

Ask:

```bash
curl -X POST http://127.0.0.1:8898/ask \
  -H 'Content-Type: application/json' \
  -d '{"session_id":"demo","username":"alice","question":"查一下G1"}'
```

The current Python action Agent supports these first tool paths through the Java service:

- train-number query
- train-number booking
- route search
- route travel advice by combining route search with weather MCP tool calls
- route booking by selecting the earliest train with available stock
- current-user order query
- order-number refund
- train-number refund by matching the user's latest refundable order for that train

High-risk tools such as booking and refunding use an explicit confirmation flow. The first request stores a pending action and returns a confirmation prompt; the action is executed only after the user replies with `确认`. Replying with `取消` clears the pending action.

Travel-advice example:

```bash
curl -X POST http://127.0.0.1:8898/ask \
  -H 'Content-Type: application/json' \
  -d '{"session_id":"demo","username":"alice","question":"明天北京到上海适合坐高铁吗"}'
```

This path calls the Java train tool and then calls the weather MCP tool for the origin and destination city. The response trace includes the MCP `tools/call` metadata for both weather lookups.

The service also includes a Python-side policy QA path backed by local policy documents. `PYTHON_AGENT_POLICY_RULES_PATH` can point to one supported file or a directory of supported files. Policy questions are routed away from the action agent and answered through lightweight local retrieval, with optional LLM synthesis when `ZHIPU_API_KEY` is configured.
Supported policy document formats:

- `.txt` / `.md` / `.markdown`: built-in parser
- `.docx`: `python-docx` parser, with a standard-library XML fallback for plain paragraphs
- `.pdf`: PyMuPDF parser for text-based PDFs
- scanned PDF: detected as OCR-required; wire in PaddleOCR / Docling later if scanned regulation files become part of the source corpus

The ingestion layer splits regulation documents by Markdown heading hierarchy and article patterns such as `第十六条`, and preserves source metadata for traceable RAG contexts.
The policy QA path now keeps lightweight in-memory session context for short follow-up questions such as `再具体一点` or `那学生票呢`.
The service now also exposes Redis-backed cache and memory adapters with automatic in-memory fallback when Redis is unavailable.
Session traces now include an optional `sessionSummary` field once enough turns have accumulated for summary compression.
Query rewrite and follow-up handling now include an explicit `contextDependency` trace, and Planned RAG planning now includes a `complexity` trace.
When `OLLAMA_ENABLED=true`, lightweight steps such as query rewrite, intent scoring, policy classification, and policy evidence judgment can use the local Ollama auxiliary model before falling back to cloud inference.

## Local auxiliary model

Recommended use of Ollama in this project:

- local intent scoring
- local query rewriting
- local policy route classification
- local policy evidence judgment

This keeps high-frequency lightweight decisions local, while stronger cloud models can still handle heavier planning and answer generation.

## Fine-tuning scaffold

The repo now includes a practical LoRA / QLoRA scaffold under [finetune](./finetune):

- [finetune/README.md](./finetune/README.md)
- [finetune/scripts/train_unsloth.py](./finetune/scripts/train_unsloth.py)
- [finetune/modelfiles/qwen2.5-agent-aux.Modelfile](./finetune/modelfiles/qwen2.5-agent-aux.Modelfile)

Recommended route:

1. Prepare narrow-task JSONL datasets for routing / rewriting / policy gating
2. Train `Qwen2.5` with `Unsloth` on a cloud `4090 24GB` or `A6000 48GB`
3. Export the adapter and load it into Ollama
4. Run the Python agent with `OLLAMA_ENABLED=true`

## Current structure

- `app/planner.py`: question parsing, route/date extraction, and tool-plan generation
- `app/llm_planner.py`: optional LLM-based plan generation with the same tool contract
- `app/tool_catalog.py`: shared tool metadata used by the LLM planner prompt
- `app/tool_executor.py`: Java ticket API calls and result normalization
- `app/weather_mcp.py`: weather MCP JSON-RPC client with local deterministic fallback for travel-advice demos
- `app/weather_mcp_server.py`: real local weather MCP server using Open-Meteo as the weather provider
- `app/response_summarizer.py`: optional LLM-based answer synthesis across multiple observations
- `app/intent_router.py`: multi-intent scoring and routing for action, ticket, policy, and chitchat
- `app/context_classifier.py`: context dependency classification for short follow-up and referential questions
- `app/conversation_bridge.py`: follow-up question bridging using recent session memory
- `app/policy_complexity.py`: complex policy-question classification before Planned RAG splitting
- `app/document_ingestion.py`: PDF/DOCX/Markdown/TXT policy-document ingestion and article-level chunking
- `app/document_ingestion_service.py`: offline ingestion job with file-hash deduplication, ingestion status tracking, optional OpenSearch bulk indexing, and optional Milvus embedding insertion
- `app/hybrid_retriever.py`: dense/sparse/RRF/rerank retrieval pipeline with real OpenSearch sparse retrieval, optional Milvus dense retrieval, and local fallback
- `app/memory_store.py`: session memory with Redis-backed and in-memory implementations
- `app/semantic_cache.py`: semantic cache with Redis-backed and in-memory implementations
- `app/summary_memory.py`: long-term summary compression over recent turns
- `app/orchestrator.py`: top-level request orchestration across router, bridge, cache, retrieval, and action execution
- `app/agent_service.py`: rule planner + optional LLM planner orchestration and final answer assembly
- `app/main.py`: FastAPI entrypoints for `/ask`, `/ask/stream`, and `/health`

## Runtime behavior

- without `ZHIPU_API_KEY`: the service uses the rule-based planner only
- with `ZHIPU_API_KEY`: the service can try LLM planning first, then fall back to the rule planner if the LLM plan is invalid
- with `ZHIPU_API_KEY` plus `PYTHON_AGENT_MILVUS_ENABLED=true`: the service can also generate live query embeddings and use the existing Milvus collection for dense retrieval
- tool execution always goes through the same Python executor and existing Java ticket APIs
- when Redis is reachable: session memory and semantic cache use Redis-backed storage
- when Redis is unavailable: the service falls back to in-process memory and cache
- policy retrieval now runs through a hybrid retrieval interface that can query the live `ticket_rules_sparse` OpenSearch index and, when embeddings are available, the live Milvus collection
- when `milvus_enabled` / `opensearch_enabled` are turned on, retrieval trace reports whether each lane used `remote` results or `local_fallback`, plus fallback reason and backend health

## Debug endpoints

- `GET /debug/runtime`: runtime config and dependency health snapshot
- `GET /debug/session/{session_id}`: recent turns and summary for one session
- `POST /debug/cache/clear`: clear all semantic cache entries or one exact-question entry
- `POST /debug/session/{session_id}/clear`: clear one session's short-term memory and summary

## Policy document ingestion

Dry-run a single file or directory before writing any remote index:

```bash
curl -X POST http://127.0.0.1:8898/admin/documents/ingest \
  -H 'Content-Type: application/json' \
  -d '{
    "source_path": "ragas_eval/12306_rules.txt",
    "dry_run": true
  }'
```

Ingest into enabled backends:

```bash
export PYTHON_AGENT_OPENSEARCH_ENABLED=true
export PYTHON_AGENT_MILVUS_ENABLED=true
export ZHIPU_API_KEY="..."

curl -X POST http://127.0.0.1:8898/admin/documents/ingest \
  -H 'Content-Type: application/json' \
  -d '{
    "source_path": "ragas_eval/12306_rules.txt",
    "write_opensearch": true,
    "write_milvus": true
  }'
```

The ingestion job records status in `PYTHON_AGENT_INGESTION_STATE_PATH` (default: `python_agent/.ingestion/jobs.json`) and skips unchanged files by SHA-256 hash unless `force=true` is passed.

Recent jobs:

```bash
curl 'http://127.0.0.1:8898/admin/documents/ingest/jobs?limit=10'
```

## Eval endpoint

- `GET /eval/ask?q=...&sessionId=...&username=...`: returns `answer`, `contexts`, and `trace` in a RAG-eval-friendly shape
