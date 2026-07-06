#!/usr/bin/env bash
set -euo pipefail

load_local_env_file() {
  local env_file="${1:-.env}"
  if [[ ! -f "$env_file" ]]; then
    return 0
  fi

  set -a
  # shellcheck disable=SC1090
  source "$env_file"
  set +a
}

resolve_docker_mode() {
  local requested="${DEV_DOCKER_MODE:-auto}"
  if [[ "$requested" == "desktop" || "$requested" == "native" ]]; then
    echo "$requested"
    return 0
  fi

  local docker_os=""
  docker_os="$(docker info --format '{{.OperatingSystem}}' 2>/dev/null || true)"
  if [[ "$docker_os" == *"Docker Desktop"* ]]; then
    echo "desktop"
    return 0
  fi

  echo "native"
}

resolve_windows_host_ip() {
  ip route | awk '/default/ { print $3; exit }'
}

configure_dev_env() {
  load_local_env_file "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/.env"

  export DEV_DOCKER_MODE_RESOLVED
  DEV_DOCKER_MODE_RESOLVED="$(resolve_docker_mode)"

  export WINDOWS_HOST_IP
  WINDOWS_HOST_IP="$(resolve_windows_host_ip)"

  if [[ "$DEV_DOCKER_MODE_RESOLVED" == "desktop" ]]; then
    export ROCKETMQ_ADVERTISE_IP="${ROCKETMQ_ADVERTISE_IP:-$WINDOWS_HOST_IP}"
    export ROCKETMQ_NAMESRV="${ROCKETMQ_NAMESRV:-$WINDOWS_HOST_IP:9876}"
    export DEV_ENV_SUMMARY="Docker Desktop backend detected; RocketMQ routes through the Windows host IP."
  else
    export ROCKETMQ_ADVERTISE_IP="${ROCKETMQ_ADVERTISE_IP:-127.0.0.1}"
    export ROCKETMQ_NAMESRV="${ROCKETMQ_NAMESRV:-127.0.0.1:9876}"
    export DEV_ENV_SUMMARY="WSL2 native Docker backend detected; RocketMQ stays on Linux-local 127.0.0.1."
  fi

  export APP_MQ_CONSUMERS_ENABLED="${APP_MQ_CONSUMERS_ENABLED:-true}"
  export ZHIPU_API_KEY="${ZHIPU_API_KEY:-YOUR_ZHIPU_API_KEY_HERE}"
  export ZHIPU_CHAT_MODEL="${ZHIPU_CHAT_MODEL:-glm-4.5-air}"
  export ZHIPU_AGENT_MODEL="${ZHIPU_AGENT_MODEL:-glm-4.5-air}"
  export ZHIPU_POLICY_MODEL="${ZHIPU_POLICY_MODEL:-glm-5.1}"
  export ZHIPU_POLICY_FAST_MODEL="${ZHIPU_POLICY_FAST_MODEL:-glm-4.5-air}"
  export ZHIPU_RERANK_MODEL="${ZHIPU_RERANK_MODEL:-rerank}"
  export ZHIPU_OCR_MODEL="${ZHIPU_OCR_MODEL:-glm-4.6v}"
  export ZHIPU_EMBEDDING_MODEL="${ZHIPU_EMBEDDING_MODEL:-embedding-3}"
}

print_dev_env() {
  echo "Docker mode: $DEV_DOCKER_MODE_RESOLVED"
  echo "Windows host IP: ${WINDOWS_HOST_IP:-unknown}"
  echo "RocketMQ broker advertise IP: $ROCKETMQ_ADVERTISE_IP"
  echo "RocketMQ NameServer: $ROCKETMQ_NAMESRV"
  echo "Zhipu chat model: $ZHIPU_CHAT_MODEL"
  echo "Zhipu agent model: $ZHIPU_AGENT_MODEL"
  echo "Zhipu policy model: $ZHIPU_POLICY_MODEL"
  echo "Zhipu policy fast model: $ZHIPU_POLICY_FAST_MODEL"
  echo "Zhipu rerank model: $ZHIPU_RERANK_MODEL"
  echo "Zhipu OCR model: $ZHIPU_OCR_MODEL"
  echo "$DEV_ENV_SUMMARY"
}
