#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUN_DIR="$ROOT_DIR/.run"
APP_PID_FILE="$RUN_DIR/spring-boot.pid"
APP_PORT="${APP_PORT:-8899}"
TMUX_SESSION_NAME="${TMUX_SESSION_NAME:-ticket-service-app}"
STOP_MIDDLEWARE="${STOP_MIDDLEWARE:-0}"

usage() {
  cat <<'EOF'
Usage: ./dev-stop.sh [--with-middleware]

Options:
  --with-middleware   Stop Docker middleware in addition to Spring Boot.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --with-middleware)
      STOP_MIDDLEWARE=1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      usage
      exit 1
      ;;
  esac
  shift
done

cd "$ROOT_DIR"

stop_pid() {
  local pid="$1"
  if [[ -z "$pid" ]]; then
    return 0
  fi
  if ! kill -0 "$pid" 2>/dev/null; then
    return 0
  fi

  echo "Stopping Spring Boot process: PID=$pid"
  kill "$pid" 2>/dev/null || true
  for _ in {1..20}; do
    if ! kill -0 "$pid" 2>/dev/null; then
      return 0
    fi
    sleep 1
  done

  echo "Force killing Spring Boot process: PID=$pid"
  kill -9 "$pid" 2>/dev/null || true
}

if tmux has-session -t "$TMUX_SESSION_NAME" 2>/dev/null; then
  echo "Stopping tmux session: $TMUX_SESSION_NAME"
  tmux kill-session -t "$TMUX_SESSION_NAME" 2>/dev/null || true
  sleep 1
fi

if [[ -f "$APP_PID_FILE" ]]; then
  stop_pid "$(cat "$APP_PID_FILE" 2>/dev/null || true)"
  rm -f "$APP_PID_FILE"
fi

PORT_PID="$(lsof -ti "tcp:${APP_PORT}" || true)"
if [[ -n "$PORT_PID" ]]; then
  stop_pid "$PORT_PID"
fi

if [[ "$STOP_MIDDLEWARE" == "1" ]]; then
  echo "Stopping Docker services..."
  docker compose -f docker-compose.dev.yml down --remove-orphans
fi

echo "Stopped."
