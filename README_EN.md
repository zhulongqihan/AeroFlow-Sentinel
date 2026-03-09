# AeroFlow Sentinel

> An AI agent system for flight booking stability analysis, incident triage, and runbook-assisted investigation.

## Overview

AeroFlow Sentinel is a Java-based AI Agent project for the flight booking domain. It combines two capabilities in one system:

1. Conversational knowledge assistant: answers questions about booking pipeline governance, incident response, and operational runbooks.
2. Flight booking stability inspection: uses a Supervisor-Planner-Executor multi-agent workflow to investigate risks across search, booking, ticketing, refund and rebooking, and GDS or supplier integrations.

The current repository is aligned with the deployed online version and can run in a lightweight demo mode on low-spec ECS machines.

## Scope

The system focuses on these flight-domain chains:

1. Flight search and pricing
2. Booking creation and PNR processing
3. Payment and ticket issuance
4. Refund and rebooking fulfillment
5. GDS and supplier gateway integrations
6. Ancillary services such as seat and baggage

## Key Features

- Multi-turn chat with tool calling
- SSE streaming responses
- Multi-agent inspection workflow with Markdown report output
- Internal runbook retrieval via RAG or local Markdown fallback
- Mock alert and log evidence for demo mode
- Lightweight deployment path for 2C2G ECS

## Architecture

### Chat Layer

- POST /api/chat
- POST /api/chat_stream

### Inspection Layer

- POST /api/flight_guard
- POST /api/campaign_guard
- POST /api/ai_ops

### Knowledge Layer

- InternalDocsTools queries Milvus when enabled
- Falls back to local aiops-docs Markdown files when Milvus is disabled

## Tech Stack

| Component | Version | Notes |
|------|------|------|
| Java | 17 | Main language |
| Spring Boot | 3.2.0 | Backend framework |
| Spring AI Alibaba | 1.1.0.0-RC2 | Agent framework |
| DashScope | 2.17.0 | LLM and embedding services |
| Milvus | 2.6.10 | Optional vector database |
| Prometheus | - | Alert source or mock source |
| Tencent CLS MCP | - | Optional remote log tooling |

## Repo Structure

```text
aeroflow-sentinel/
├── src/main/java/io/github/zhulongqihan/aeroflow/sentinel/
│   ├── agent/tool/
│   ├── client/
│   ├── config/
│   ├── constant/
│   ├── controller/
│   ├── dto/
│   ├── service/
│   ├── tool/
│   └── AeroFlowSentinelApplication.java
├── src/main/resources/
│   ├── static/
│   ├── application.yml
│   └── application-demo.yml
├── aiops-docs/
├── docs/
├── vector-database.yml
└── Makefile
```

## Build and Run

### Environment Variable

On Linux or macOS:

```bash
export DASHSCOPE_API_KEY=your-api-key
```

On PowerShell:

```powershell
$env:DASHSCOPE_API_KEY="your-api-key"
```

### Package

```bash
mvn clean package -DskipTests
```

### Run Demo Profile

```bash
java -jar target/aeroflow-sentinel-1.0-SNAPSHOT.jar --spring.profiles.active=demo
```

### Optional Full Mode

```bash
docker compose up -d -f vector-database.yml
java -jar target/aeroflow-sentinel-1.0-SNAPSHOT.jar
```

## Live Access

- http://agent.cyruszhang.online

## License

MIT
