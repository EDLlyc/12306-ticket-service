#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT_DIR/.run"
APP_LOG_FILE="$RUN_DIR/python-agent.log"
TMUX_SESSION_NAME="${PYTHON_AGENT_TMUX_SESSION_NAME:-python-agent}"
RUN_MODE="${PYTHON_AGENT_RUN_MODE:-foreground}"
cd "$ROOT_DIR"

if [[ -f "$ROOT_DIR/dev-env.sh" ]]; then
  # shellcheck disable=SC1091
  source "$ROOT_DIR/dev-env.sh"
  configure_dev_env
fi

source .venv/bin/activate
mkdir -p "$RUN_DIR"

export PYTHON_AGENT_MILVUS_ENABLED="${PYTHON_AGENT_MILVUS_ENABLED:-true}"
export PYTHON_AGENT_OPENSEARCH_ENABLED="${PYTHON_AGENT_OPENSEARCH_ENABLED:-true}"
export MILVUS_URI="${MILVUS_URI:-http://127.0.0.1:19530}"
export MILVUS_COLLECTION_NAME="${MILVUS_COLLECTION_NAME:-rules_embedding3_1024}"
export OPENSEARCH_ENDPOINT="${OPENSEARCH_ENDPOINT:-http://127.0.0.1:29200}"
export OPENSEARCH_INDEX="${OPENSEARCH_INDEX:-ticket_rules_sparse}"
export REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
export REDIS_PORT="${REDIS_PORT:-26379}"
export REDIS_DB="${REDIS_DB:-0}"
export WEATHER_MCP_HOST="${WEATHER_MCP_HOST:-127.0.0.1}"
export WEATHER_MCP_PORT="${WEATHER_MCP_PORT:-8897}"
export WEATHER_MCP_ENDPOINT="${WEATHER_MCP_ENDPOINT:-http://$WEATHER_MCP_HOST:$WEATHER_MCP_PORT/mcp}"
export WEATHER_MCP_TOOL_NAME="${WEATHER_MCP_TOOL_NAME:-get_weather_by_city}"

if [[ "${1:-}" == "--tmux" ]]; then
  RUN_MODE="tmux"
fi

if [[ "$RUN_MODE" == "tmux" ]]; then
  tmux kill-session -t "$TMUX_SESSION_NAME" 2>/dev/null || true
  printf -v TMUX_COMMAND 'cd %q && exec uvicorn python_agent.app.main:app --host %q --port %q >> %q 2>&1' \
    "$ROOT_DIR" \
    "${PYTHON_AGENT_HOST:-0.0.0.0}" \
    "${PYTHON_AGENT_PORT:-8898}" \
    "$APP_LOG_FILE"
  tmux new-session -d -s "$TMUX_SESSION_NAME" "$TMUX_COMMAND"
  echo "python-agent tmux session: $TMUX_SESSION_NAME"
  echo "python-agent log: $APP_LOG_FILE"
  exit 0
fi

exec uvicorn python_agent.app.main:app \
  --host "${PYTHON_AGENT_HOST:-0.0.0.0}" \
  --port "${PYTHON_AGENT_PORT:-8898}" \
  --reload 2>&1 | tee "$APP_LOG_FILE"
