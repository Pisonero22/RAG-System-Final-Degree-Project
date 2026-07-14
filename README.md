# TFG — RAG Chat Assistant with Quarkus, LangChain4j & WebSockets

Bachelor's Thesis (TFG) in **Computer Engineering** at the **Universidad Pontificia de Salamanca (UPSA)**.

**Thesis title:** *LangChain4j: Integration of Quarkus and LLMs using RAG and WebSockets*

Real-time chat application that combines **Retrieval-Augmented Generation (RAG)** with multiple Large Language Model (LLM) providers. Documents are indexed as embeddings in **Redis Stack**, and the assistant answers using context retrieved from that knowledge base.

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
- [Author](#author)
- [License](#license)

---

## Features

- Real-time chat via **WebSockets** (`/chat/{username}`)
- **RAG** with semantic retrieval from Redis (RediSearch + embeddings)
- **Multiple LLM providers:**
  - **Ollama** → Llama 3.2 (default)
  - **OpenAI** → GPT-4
  - **DeepSeek** → deepseek-r1:7b (via Ollama)
  - **Mistral** → mistral:instruct (via Ollama)
- Document ingestion for `.txt`, `.csv`, and `.pdf` files
- Built-in web UI with model selector, file upload, and ingest/reset actions
- **Prompt injection guardrails** (LangChain4j Input Guardrails)
- Modular architecture with swappable LLM and storage providers

---

## Tech Stack

| Category       | Technology |
|----------------|------------|
| Framework      | [Quarkus](https://quarkus.io/) 3.19 |
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
                  │   (LLMs)   │   │  (GPT-4)   │   │(Embeddings)│
                  └────────────┘   └────────────┘   └────────────┘
```

### RAG Pipeline

1. Documents (TXT, CSV, PDF) are loaded from `main/src/main/resources/rag/`
2. They are split into chunks and converted into embeddings
3. Vectors are stored in **Redis Stack**
4. On each query, the most relevant chunks are retrieved (similarity ≥ 0.78)
5. Context is injected into the prompt and the selected LLM generates the answer

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
ollama pull mistral:instruct
ollama pull deepseek-r1:7b
```

---

## Quick Start

### Option A — Full deploy script (recommended)

Downloads models, starts Redis, and launches Quarkus in dev mode:

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

---

## Configuration

Main config file: `main/src/main/resources/application.properties`

### Default provider (Ollama)

```properties
quarkus.langchain4j.chat-model.provider=ollama
quarkus.langchain4j.embedding-model.provider=ollama
quarkus.langchain4j.ollama.embedding-model.model-id=mxbai-embed-large
quarkus.langchain4j.redis.dimension=1024
```

### Switch to OpenAI

Uncomment and set in `application.properties`:

```properties
quarkus.langchain4j.openai.api-key=YOUR_API_KEY
quarkus.langchain4j.chat-model.provider=openai
quarkus.langchain4j.embedding-model.provider=openai
quarkus.langchain4j.openai.chat-model.model-name=gpt-4
quarkus.langchain4j.redis.dimension=1536
quarkus.langchain4j.openai.embedding-model.model-name=text-embedding-ada-002
```

> **Important:** If you change the embedding provider, run **Reset Storage** from the web UI to reindex documents with the new vector dimensions.

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

## Author

**[Your Name]**  
Computer Engineering — Universidad Pontificia de Salamanca (UPSA)  
Bachelor's Thesis, 2025/2026

---

## License

This project is a Bachelor's Thesis developed for academic purposes.
