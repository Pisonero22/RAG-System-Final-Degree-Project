#!/usr/bin/env bash
set -euo pipefail

cleanup() {
  echo
  echo "Stopping Docker Compose services…"
  docker compose down
}
trap cleanup EXIT

if ! command -v ollama >/dev/null 2>&1; then
  echo "Ollama is not installed or not on PATH."
  exit 1
fi

echo "Starting Docker Compose services in the background…"
docker compose up -d

echo "Waiting for Redis to answer PONG…"
until docker exec redis redis-cli ping 2>/dev/null | grep -q PONG; do
  sleep 1
done

echo "Redis is ready."

if [[ -d main ]]; then
  echo "Entering the 'main' module to launch Quarkus…"
  cd main
else
  echo "Could not find the 'main' directory."
  exit 1
fi

echo "Starting Quarkus in dev mode… (Ctrl+C to quit)"
../mvnw quarkus:dev