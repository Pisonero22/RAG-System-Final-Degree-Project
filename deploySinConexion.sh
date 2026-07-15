#!/usr/bin/env bash
set -euo pipefail

cleanup() {
  echo
  echo "Deteniendo servicios de Docker Compose…"
  docker compose down --rmi local -v
}
trap cleanup EXIT

if ! command -v ollama >/dev/null 2>&1; then
  echo "Ollama no está instalado o no está en PATH."
  exit 1
fi

echo "Levantando servicios de Docker Compose en background…"
docker compose up -d

echo "Esperando a que Redis responda PONG…"
until docker exec redis redis-cli ping 2>/dev/null | grep -q PONG; do
  sleep 1
done

echo "Redis está listo."

# Entrar al módulo Quarkus
if [[ -d main ]]; then
  echo "Entrando al módulo 'main' para lanzar Quarkus…"
  cd main
else
  echo "No he encontrado el directorio 'main'."
  exit 1
fi

echo "Iniciando Quarkus en modo dev… (Ctrl+C para salir)"
mvn quarkus:dev
