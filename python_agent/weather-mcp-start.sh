#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT_DIR/.run"
LOG_FILE="$RUN_DIR/weather-mcp.log"
RUN_MODE="${WEATHER_MCP_RUN_MODE:-foreground}"
TMUX_SESSION_NAME="${WEATHER_MCP_TMUX_SESSION_NAME:-weather-mcp}"
cd "$ROOT_DIR"

source .venv/bin/activate
mkdir -p "$RUN_DIR"

export WEATHER_MCP_HOST="${WEATHER_MCP_HOST:-127.0.0.1}"
export WEATHER_MCP_PORT="${WEATHER_MCP_PORT:-8897}"

if [[ "${1:-}" == "--tmux" ]]; then
  RUN_MODE="tmux"
fi

if [[ "$RUN_MODE" == "tmux" ]]; then
  tmux kill-session -t "$TMUX_SESSION_NAME" 2>/dev/null || true
  printf -v TMUX_COMMAND 'cd %q && exec uvicorn python_agent.app.weather_mcp_server:app --host %q --port %q >> %q 2>&1' \
    "$ROOT_DIR" \
    "$WEATHER_MCP_HOST" \
    "$WEATHER_MCP_PORT" \
    "$LOG_FILE"
  tmux new-session -d -s "$TMUX_SESSION_NAME" "$TMUX_COMMAND"
  echo "weather-mcp tmux session: $TMUX_SESSION_NAME"
  echo "weather-mcp endpoint: http://$WEATHER_MCP_HOST:$WEATHER_MCP_PORT/mcp"
  echo "weather-mcp log: $LOG_FILE"
  exit 0
fi

exec uvicorn python_agent.app.weather_mcp_server:app \
  --host "$WEATHER_MCP_HOST" \
  --port "$WEATHER_MCP_PORT" \
  --reload 2>&1 | tee "$LOG_FILE"
