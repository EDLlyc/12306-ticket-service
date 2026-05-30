#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUN_DIR="$ROOT_DIR/.run"
APP_PID_FILE="$RUN_DIR/spring-boot.pid"
APP_LOG_FILE="$RUN_DIR/spring-boot.log"
APP_PORT="${APP_PORT:-8899}"
START_TIMEOUT_SECONDS="${START_TIMEOUT_SECONDS:-120}"
WAIT_TIMEOUT_SECONDS="${WAIT_TIMEOUT_SECONDS:-180}"
MVN_BUILD_ARGS="${MVN_BUILD_ARGS:--DskipTests package}"
TMUX_SESSION_NAME="${TMUX_SESSION_NAME:-ticket-service-app}"
RUN_MODE="${APP_RUN_MODE:-foreground}"

resolve_local_access_url() {
  echo "http://localhost:${APP_PORT}/"
}

resolve_wsl_ip() {
  hostname -I 2>/dev/null | awk '{print $1}'
}

print_access_urls() {
  local local_url
  local_url="$(resolve_local_access_url)"
  echo "Local URL: $local_url"

  local wsl_ip
  wsl_ip="$(resolve_wsl_ip)"
  if [[ -n "${wsl_ip:-}" ]]; then
    echo "WSL URL: http://${wsl_ip}:${APP_PORT}/"
  fi
}

usage() {
  cat <<'EOF'
Usage: ./dev-start.sh [--foreground|--tmux]

Options:
  --foreground   Run Spring Boot in the current terminal. This is the default.
  --tmux         Run Spring Boot in a detached tmux session.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --foreground)
      RUN_MODE="foreground"
      ;;
    --tmux)
      RUN_MODE="tmux"
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

mkdir -p "$RUN_DIR"

cd "$ROOT_DIR"
source "$ROOT_DIR/dev-env.sh"
configure_dev_env

print_dev_env
echo "Spring Boot port: $APP_PORT"
echo "Zhipu embedding model: $ZHIPU_EMBEDDING_MODEL"

echo "Starting middleware..."
docker compose -f "$ROOT_DIR/docker-compose.dev.yml" up -d

wait_for_container() {
  local name="$1"
  local require_healthy="$2"
  local status=""

  for ((i = 1; i <= WAIT_TIMEOUT_SECONDS; i++)); do
    status="$(docker inspect --format='{{.State.Status}}{{if .State.Health}} {{.State.Health.Status}}{{end}}' "$name" 2>/dev/null || true)"
    if [[ -z "$status" ]]; then
      sleep 1
      continue
    fi

    if [[ "$require_healthy" == "healthy" ]]; then
      if [[ "$status" == "running healthy" ]]; then
        echo "Ready: $name ($status)"
        return 0
      fi
    else
      if [[ "$status" == running* ]]; then
        echo "Ready: $name ($status)"
        return 0
      fi
    fi

    sleep 1
  done

  echo "Timed out waiting for $name. Last status: ${status:-unknown}"
  return 1
}

wait_for_container ticket-mysql healthy
wait_for_container ticket-mysql-proxy running
wait_for_container ticket-redis healthy
wait_for_container ticket-opensearch healthy
wait_for_container milvus-etcd healthy
wait_for_container milvus-minio healthy
wait_for_container milvus-standalone healthy
wait_for_container ticket-rocketmq-namesrv running
wait_for_container ticket-rocketmq-broker running
wait_for_container ticket-sentinel-dashboard running

echo "All middleware services are up."

resolve_app_jar() {
  find "$ROOT_DIR/target" -maxdepth 1 -type f -name "*.jar" \
    ! -name "*-sources.jar" \
    ! -name "*-javadoc.jar" \
    ! -name "*.original" \
    | sort | tail -n 1
}

tmux_session_exists() {
  tmux has-session -t "$TMUX_SESSION_NAME" 2>/dev/null
}

stop_tmux_session_if_exists() {
  if tmux_session_exists; then
    echo "Stopping stale tmux session: $TMUX_SESSION_NAME"
    tmux kill-session -t "$TMUX_SESSION_NAME" 2>/dev/null || true
    sleep 1
  fi
}

if [[ -f "$APP_PID_FILE" ]]; then
  EXISTING_PID="$(cat "$APP_PID_FILE" 2>/dev/null || true)"
  if [[ -n "$EXISTING_PID" ]] && kill -0 "$EXISTING_PID" 2>/dev/null; then
    if curl -fsS "http://127.0.0.1:${APP_PORT}/actuator/health" >/dev/null 2>&1; then
      echo "Spring Boot is already running. PID=$EXISTING_PID"
      if [[ "$RUN_MODE" == "tmux" ]]; then
        echo "tmux session: $TMUX_SESSION_NAME"
      fi
      print_access_urls
      exit 0
    fi
    echo "Stopping stale Spring Boot process: PID=$EXISTING_PID"
    kill "$EXISTING_PID" 2>/dev/null || true
    sleep 2
  fi
  rm -f "$APP_PID_FILE"
fi

if tmux_session_exists; then
  if curl -fsS "http://127.0.0.1:${APP_PORT}/actuator/health" >/dev/null 2>&1; then
    echo "Spring Boot is already running in tmux session: $TMUX_SESSION_NAME"
    print_access_urls
    exit 0
  fi
  stop_tmux_session_if_exists
fi

PORT_PID="$(lsof -ti "tcp:${APP_PORT}" || true)"
if [[ -n "$PORT_PID" ]]; then
  if curl -fsS "http://127.0.0.1:${APP_PORT}/actuator/health" >/dev/null 2>&1; then
    echo "Application is already reachable on port ${APP_PORT}. PID=${PORT_PID}"
    print_access_urls
    exit 0
  fi
  echo "Port ${APP_PORT} is already occupied by PID ${PORT_PID}. Stop it first, then retry."
  exit 1
fi

rm -f "$APP_LOG_FILE"

echo "Building Spring Boot executable jar..."
mvn $MVN_BUILD_ARGS

APP_JAR_FILE="$(resolve_app_jar)"
if [[ -z "${APP_JAR_FILE:-}" ]]; then
  echo "Failed to locate executable jar under target/ after build."
  exit 1
fi

if [[ "$RUN_MODE" == "foreground" ]]; then
  rm -f "$APP_PID_FILE"
  echo "Starting Spring Boot executable jar in foreground (current terminal)..."
  echo "Access URLs after startup:"
  print_access_urls
  echo "Log: $APP_LOG_FILE"
  echo "Press Ctrl+C to stop the application."
  env \
    APP_MQ_CONSUMERS_ENABLED="$APP_MQ_CONSUMERS_ENABLED" \
    ROCKETMQ_NAMESRV="$ROCKETMQ_NAMESRV" \
    ZHIPU_API_KEY="$ZHIPU_API_KEY" \
    ZHIPU_CHAT_MODEL="$ZHIPU_CHAT_MODEL" \
    ZHIPU_AGENT_MODEL="$ZHIPU_AGENT_MODEL" \
    ZHIPU_POLICY_MODEL="$ZHIPU_POLICY_MODEL" \
    ZHIPU_POLICY_FAST_MODEL="$ZHIPU_POLICY_FAST_MODEL" \
    ZHIPU_RERANK_MODEL="$ZHIPU_RERANK_MODEL" \
    ZHIPU_OCR_MODEL="$ZHIPU_OCR_MODEL" \
    ZHIPU_EMBEDDING_MODEL="$ZHIPU_EMBEDDING_MODEL" \
    java -jar "$APP_JAR_FILE" 2>&1 | tee "$APP_LOG_FILE"
  exit ${PIPESTATUS[0]}
fi

stop_tmux_session_if_exists

printf -v TMUX_COMMAND 'cd %q && exec env APP_MQ_CONSUMERS_ENABLED=%q ROCKETMQ_NAMESRV=%q ZHIPU_API_KEY=%q ZHIPU_CHAT_MODEL=%q ZHIPU_AGENT_MODEL=%q ZHIPU_POLICY_MODEL=%q ZHIPU_POLICY_FAST_MODEL=%q ZHIPU_RERANK_MODEL=%q ZHIPU_OCR_MODEL=%q ZHIPU_EMBEDDING_MODEL=%q java -jar %q >> %q 2>&1' \
  "$ROOT_DIR" \
  "$APP_MQ_CONSUMERS_ENABLED" \
  "$ROCKETMQ_NAMESRV" \
  "$ZHIPU_API_KEY" \
  "$ZHIPU_CHAT_MODEL" \
  "$ZHIPU_AGENT_MODEL" \
  "$ZHIPU_POLICY_MODEL" \
  "$ZHIPU_POLICY_FAST_MODEL" \
  "$ZHIPU_RERANK_MODEL" \
  "$ZHIPU_OCR_MODEL" \
  "$ZHIPU_EMBEDDING_MODEL" \
  "$APP_JAR_FILE" \
  "$APP_LOG_FILE"

echo "Starting Spring Boot executable jar in tmux session: $TMUX_SESSION_NAME"
tmux new-session -d -s "$TMUX_SESSION_NAME" "$TMUX_COMMAND"

sleep 1
APP_PID="$(tmux display-message -p -t "$TMUX_SESSION_NAME:0.0" "#{pane_pid}" 2>/dev/null || true)"
if [[ -z "${APP_PID:-}" ]]; then
  echo "Failed to capture application PID from tmux session."
  tmux capture-pane -pt "$TMUX_SESSION_NAME" || true
  exit 1
fi

echo "$APP_PID" > "$APP_PID_FILE"

echo "Waiting for application health check..."
for ((i = 1; i <= START_TIMEOUT_SECONDS; i++)); do
  if curl -fsS "http://127.0.0.1:${APP_PORT}/actuator/health" >/dev/null 2>&1; then
    echo "Application started successfully. PID=$APP_PID"
    echo "tmux session: $TMUX_SESSION_NAME"
    print_access_urls
    echo "Log: $APP_LOG_FILE"
    echo "Attach: tmux attach -t $TMUX_SESSION_NAME"
    exit 0
  fi

  if ! tmux_session_exists || ! kill -0 "$APP_PID" 2>/dev/null; then
    echo "Spring Boot exited unexpectedly. Recent log:"
    tail -n 80 "$APP_LOG_FILE" || true
    tmux capture-pane -pt "$TMUX_SESSION_NAME" 2>/dev/null || true
    exit 1
  fi

  sleep 1
done

echo "Application did not become healthy within ${START_TIMEOUT_SECONDS}s. Recent log:"
tail -n 80 "$APP_LOG_FILE" || true
exit 1
