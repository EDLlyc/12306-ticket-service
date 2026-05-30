#!/usr/bin/env bash

set -euo pipefail

HOST="${HOST:-127.0.0.1}"
PORT="${PORT:-8899}"

while IFS= read -r train_number; do
  if [[ -z "${train_number}" || "${train_number}" == "trainNumber" ]]; then
    continue
  fi

  echo "Preheating ${train_number}..."
  curl --silent --show-error "http://${HOST}:${PORT}/train/init?trainNumber=${train_number}"
  echo
done < train-numbers.csv
