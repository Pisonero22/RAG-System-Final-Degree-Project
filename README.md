# RAG System — Final Degree Project

[![CI](https://github.com/Pisonero22/RAG-System-Final-Degree-Project/actions/workflows/ci.yml/badge.svg)](https://github.com/Pisonero22/RAG-System-Final-Degree-Project/actions/workflows/ci.yml)
Hybrid retrieval-augmented generation system over a local document corpus:
dense vector search (bge-m3 + RediSearch) fused with BM25 lexical search via
Reciprocal Rank Fusion, served through Quarkus and local LLMs on Ollama.



## Arquitectura

### Pipeline de consulta

```mermaid
flowchart TD
    U([Usuario]) -->|WebSocket| WS[ChatWebSocket]
    WS --> CS[ChatService]
    CS --> G{"Guardrail<br/>llama3.1:8b, temp 0"}
    G -->|score &gt; 0.89| BLOCK([Mensaje bloqueado])
    G -->|score ≤ 0.89| QR["QueryRewriteService<br/>(solo si hay historial)"]
    QR --> PG{"¿Solo cambia<br/>puntuación?"}
    PG -->|sí| ORIG[Pregunta original]
    PG -->|no| REW[Consulta reescrita]
    ORIG --> RET[RagRetriever]
    REW --> RET
    RET --> D["DenseSearch<br/>bge-m3 · KNN · minScore 0.75"]
    RET --> L["LexicalSearch<br/>FT.SEARCH · AND · palabras vacías"]
    D --> F["RrfFusion<br/>k=60 · 1/(k+puesto)"]
    L --> F
    F -->|top 3| CTX["Contexto → SYSTEM MESSAGE"]
    CTX --> A["RagAssistant<br/>slot seleccionado"]
    A --> R([Respuesta])
```

### Pipeline de ingesta

```mermaid
flowchart LR
    subgraph Corpus["data/rag/"]
        CSV[csv/]
        TXT[txt/]
        PDF[pdf/]
    end
    CSV --> CL["CsvDocumentLoader<br/>1 fila = 1 documento"]
    TXT --> TL[TxtDocumentLoader]
    PDF --> PL["PdfDocumentLoader<br/>+ puente de 300 car.<br/>entre páginas"]

    CL -->|sin segmentador| ING
    TL --> SP["DocumentSplitters.recursive<br/>512 / 128"]
    PL --> SP
    SP --> ING["textSegmentTransformer<br/>normalizeLineBreaks"]
    ING --> EM["bge-m3<br/>1024 dims"]
    EM --> RS[("RediSearch<br/>embedding-index")]
```




## Author

Final Degree Project — Alejandro Pisonero
Universidad Pontificia de Salamanca, 2025–2026
