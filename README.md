# TFG — RAG Chat Assistant with Quarkus, LangChain4j & WebSockets

Bachelor's Thesis (TFG) in **Computer Engineering** at the **Universidad Pontificia de Salamanca (UPSA)**.

**Thesis title:** *LangChain4j: Integration of Quarkus and LLMs using RAG and WebSockets*

Real-time chat application that combines Retrieval-Augmented Generation (RAG) with multiple Large Language Model (LLM) providers. Documents are indexed as embeddings in Redis Stack, and the assistant answers using context retrieved from that knowledge base.

---

## Table of Contents

- [Features](#features)
- [Tech Stack](#tech-stack)
- [Architecture](#architecture)
- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [Manual Setup](#manual-setup)
- [Configuration](#configuration)
- [API Reference](#api-reference)
- [Project Structure](#project-structure)
- [Web UI Usage](#web-ui-usage)
- [Build & Package](#build--package)
- [Good to Know](#good-to-know)
- [Author](#author)
- [License](#license)

---

## Features

- Real-time chat over WebSockets (`/chat/{username}`)
- RAG retrieval backed by Redis Stack (RediSearch + vector similarity)
- Four selectable LLM backends: **Ollama** (Llama 3.2), **OpenAI** (GPT-4), **DeepSeek** (r1:7b) and **Mistral** (Instruct) — the last two also run through Ollama, just with different models
- Upload your own `.txt`, `.csv` or `.pdf` files and add them to the knowledge base on the fly
- Small web UI (PatternFly + Bootstrap) with a model picker, connect/send, upload, and ingest/reset controls
- A prompt-injection detection guardrail built with LangChain4j (implemented, though not switched on by default — see [Good to Know](#good-to-know))

---

## Tech Stack

| Category       | Technology |
|----------------|------------|
| Framework      | [Quarkus](https://quarkus.io/) 3.19.1 |
| AI / LLM       | [LangChain4j](https://github.com/langchain4j/langchain4j) 1.0.0-beta2 |
| Language       | Java 21 |
| Vector Store   | Redis Stack (RediSearch) |
| Local LLMs     | [Ollama](https://ollama.ai/) |
| Communication  | WebSockets (Quarkus) |
| Frontend       | PatternFly + Bootstrap |
| Build Tool     | Maven |

---

## Architecture

```
┌─────────────┐     WebSocket      ┌──────────────────┐
│  Frontend   │ ◄────────────────► │  ChatWebSocket   │
│ (index.html)│                    │    (Quarkus)     │
└─────────────┘                    └────────┬─────────┘
                                            │
                                   ┌────────▼─────────┐
                                   │   ServicioAI     │
                                   │  (RAG + LLM)     │
                                   └────────┬─────────┘
                         ┌─────────────────┼─────────────────┐
                         ▼                 ▼                 ▼
                  ┌────────────┐   ┌────────────┐   ┌────────────┐
                  │   Ollama   │   │   OpenAI   │   │   Redis    │
                  │ Llama 3.2  │   │   GPT-4    │   │(Embeddings │
                  │ DeepSeek,  │   │            │   │ + vectors) │
                  │  Mistral   │   │            │   │            │
                  └────────────┘   └────────────┘   └────────────┘
```

### RAG Pipeline

1. Documents (txt, csv, pdf) are loaded from `main/src/main/resources/rag/`
2. They're split into chunks (256 characters, 64 overlap) and turned into embeddings
3. The vectors go into Redis Stack
4. For each question, the top 2 chunks with similarity ≥ 0.78 are retrieved
5. Those chunks get stitched into the prompt before it's sent to whichever model is selected

---

## Prerequisites

| Tool   | Minimum Version | Purpose |
|--------|-----------------|---------|
| Java   | 21              | Application runtime |
| Maven  | 3.8+            | Build and execution |
| Docker | 20+             | Redis Stack |
| Ollama | Latest          | Local LLM models |

### Required Ollama Models

```bash
ollama pull llama3.2
ollama pull mxbai-embed-large
ollama pull bge-large
ollama pull nomic-embed-text
ollama pull mistral:instruct
ollama pull deepseek-r1:7b
```

---

## Quick Start

### Option A — Full deploy script (recommended)

Pulls the Ollama models, starts Redis, and launches Quarkus in dev mode:

```bash
chmod +x deploy.sh
./deploy.sh
```

### Option B — Deploy without downloading models

Use this if the Ollama models are already installed:

```bash
chmod +x deploySinConexion.sh
./deploySinConexion.sh
```

The app will be available at **http://localhost:8080**

---

## Manual Setup

```bash
# 1. Start Redis
docker compose up -d

# 2. Run the application
cd main
./mvnw quarkus:dev
```

Redis Stack also exposes RedisInsight on **http://localhost:8001** if you want to look at the index directly.

---

## Configuration

Main config file: `main/src/main/resources/application.properties`

As checked in, the project is set up to use **OpenAI**, so you'll need to add your API key before it runs:

```properties
quarkus.langchain4j.openai.api-key=YOUR_API_KEY
quarkus.langchain4j.chat-model.provider=openai
quarkus.langchain4j.embedding-model.provider=openai
quarkus.langchain4j.openai.chat-model.model-name=gpt-4
quarkus.langchain4j.redis.dimension=1536
quarkus.langchain4j.openai.embedding-model.model-name=text-embedding-ada-002
```

To run everything locally instead, comment out the OpenAI block above and uncomment the Ollama one:

```properties
quarkus.langchain4j.chat-model.provider=ollama
quarkus.langchain4j.embedding-model.provider=ollama
quarkus.langchain4j.ollama.embedding-model.model-id=mxbai-embed-large
quarkus.langchain4j.redis.dimension=1024
```

> **Important:** whenever you switch the embedding provider, hit **Reset Storage** from the web UI — OpenAI's `text-embedding-ada-002` and Ollama's `mxbai-embed-large` produce vectors of different sizes (1536 vs 1024), so the old index won't match anymore.

---

## API Reference

### REST Endpoints

| Method | Endpoint          | Description |
|--------|-------------------|-------------|
| `GET`  | `/service/ingest` | Ingest documents into Redis |
| `GET`  | `/service/reset`  | Reset the index and reingest all documents |
| `POST` | `/service/upload` | Upload a file (`.txt`, `.csv`, `.pdf`) |

### WebSocket

| Endpoint           | Description |
|--------------------|-------------|
| `/chat/{username}` | Real-time chat with the AI assistant |

**Message format:**

```json
{
  "type": "CHAT_MESSAGE",
  "from": "username",
  "message": "Your question here",
  "llm": "ollama"
}
```

Valid values for `llm`: `ollama`, `openai`, `deepseek`, `mistral`

---

## Project Structure

```
TFG/
├── pom.xml                         # Root POM (multi-module)
├── docker-compose.yml              # Redis Stack
├── deploy.sh                       # Full deployment script
├── deploySinConexion.sh            # Deploy without pulling models
└── main/
    ├── pom.xml
    └── src/main/
        ├── java/es/upsa/
        │   ├── ServicioAI.java                 # Main REST + RAG service
        │   ├── webSockets/
        │   │   └── ChatWebSocket.java          # Chat WebSocket handler
        │   ├── ragConfiguration/
        │   │   ├── RagAsssistant.java          # RAG assistant interface
        │   │   └── RagRetriever.java           # Context retrieval logic
        │   ├── configuration/                  # LLM model configuration
        │   ├── providers/                      # LLM & storage providers
        │   ├── store/                          # Document loading & ingestion
        │   └── guardrail/                      # Prompt injection detection
        └── resources/
            ├── application.properties
            ├── rag/                            # RAG knowledge base
            │   ├── txt/
            │   ├── csv/
            │   └── pdf/
            └── META-INF/resources/
                └── index.html                  # Web chat UI
```

---

## Web UI Usage

1. Open **http://localhost:8080**
2. Select an **LLM model** from the dropdown
3. Enter your **username** and click **Connect**
4. Send messages — the AI replies using RAG context
5. **Upload File** → add documents to the knowledge base
6. **Ingest Data** → index documents into Redis
7. **Reset Storage** → wipe the index and reingest everything

---

## Build & Package

### Standard JAR

```bash
cd main
./mvnw package
java -jar target/quarkus-app/quarkus-run.jar
```

### Native executable (GraalVM)

```bash
cd main
./mvnw package -Dnative -Dquarkus.native.container-build=true
./target/main-1.0.0-runner
```

---

## Good to Know

A few implementation details worth knowing if you dig into the code:

- The prompt-injection guardrail (`PromptInjectionGuard` + `PromptInjectionDetectionService`) is fully implemented, but the `@InputGuardrails` annotation on `RagAsssistant` is commented out, so it isn't active yet. Uncomment it to turn on validation.
- Retrieval always uses the embedding model configured globally in `application.properties`, not the one tied to whichever chat provider is selected in the dropdown — the per-provider `getEmbeddingModel()` methods aren't wired into the retrieval flow yet.
- `deploy.sh` doesn't currently pull `nomic-embed-text`, which the DeepSeek configuration needs for embeddings. Pull it manually the first time (`ollama pull nomic-embed-text`) or add it to the script.

---

## Author

**Alejandro**
Computer Engineering — Universidad Pontificia de Salamanca (UPSA)
Bachelor's Thesis, 2025/2026

---

## License

This project is a Bachelor's Thesis developed for academic purposes.
