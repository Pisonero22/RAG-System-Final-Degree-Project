#!/usr/bin/env bash
set -euo pipefail

cleanup() {
  echo
  echo "Deteniendo servicios de Docker Compose…"
  docker compose down  # sin -v (datos intactos) y sin --rmi (imagen cacheada)
}
trap cleanup EXIT

if ! command -v ollama >/dev/null 2>&1; then
  echo "Ollama no está instalado o no está en PATH."
  exit 1
fi

echo "Descargando modelos Ollama…"
ollama pull bge-m3             # embeddings (obligatorio)
ollama pull llama3.1:8b        # modelo por defecto (detector + reescritor) y slot 'llama'
ollama pull mistral:instruct   # comparativa
ollama pull deepseek-r1:7b     # comparativa
ollama pull llama3.2           # baseline 3B de la comparativa (bórralo si no lo usas)


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
./mvnw quarkus:dev
