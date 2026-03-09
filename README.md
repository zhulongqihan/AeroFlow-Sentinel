# AeroFlow Sentinel

> An AI agent system for flight booking stability analysis, incident triage, and runbook-assisted investigation.

## Overview

AeroFlow Sentinel is a Java-based AI Agent project for the flight booking domain. It combines two capabilities in one system:

1. Conversational knowledge assistant: answers questions about booking pipeline governance, incident response, and operational runbooks.
2. Flight booking stability inspection: uses a Supervisor-Planner-Executor multi-agent workflow to investigate risks across search, booking, ticketing, refund and rebooking, and GDS or supplier integrations.

The current repository is already aligned with the deployed online version and can run in a lightweight demo mode on low-spec ECS machines.

## Current Scope

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

### 1. Chat Layer

- POST /api/chat
- POST /api/chat_stream

Uses ChatController plus ChatService to build a ReactAgent with domain tools.

### 2. Inspection Layer

- POST /api/flight_guard

Compatible legacy routes are still kept for older frontends or demos:

- POST /api/campaign_guard
- POST /api/ai_ops

This path triggers a Supervisor-Planner-Executor workflow and returns a flight booking stability report as streamed Markdown.

### 3. Knowledge Layer

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
├── src/main/java/org/example/
│   ├── controller/
│   ├── service/
│   ├── agent/tool/
│   ├── config/
│   ├── client/
│   ├── constant/
│   └── dto/
├── src/main/resources/
│   ├── static/
│   ├── application.yml
│   └── application-demo.yml
├── aiops-docs/
├── docs/
├── vector-database.yml
└── Makefile
```

Note:
The repository branding, Maven coordinates, and generated jar name are all aligned to AeroFlow Sentinel.

## APIs

### Chat APIs

```bash
POST /api/chat
POST /api/chat_stream
```

### Inspection API

```bash
POST /api/flight_guard
```

### File and Session APIs

```bash
POST /api/upload
POST /api/chat/clear
GET /api/chat/session/{sessionId}
GET /milvus/health
```

## Demo Mode

The recommended deployment mode for a low-spec server is the demo profile:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=demo
```

In demo mode:

- Milvus is disabled
- MCP client is disabled
- Prometheus alerts use mock data
- CLS logs use mock data
- Internal docs retrieval uses local Markdown fallback

## Build and Run

### Environment Variable

```bash
export DASHSCOPE_API_KEY=your-api-key
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

## Example Requests

```bash
curl -X POST http://localhost:9900/api/chat \
  -H "Content-Type: application/json" \
  -d '{"Id":"test","Question":"出票失败率升高时应该先检查什么？"}'
```

```bash
curl -N -X POST http://localhost:9900/api/flight_guard
```

```bash
curl -X POST http://localhost:9900/api/upload \
  -F "file=@aiops-docs/flight_search_latency_spike.md"
```

## Live Access

The current online deployment is available at:

- http://agent.cyruszhang.online

## Open Source Positioning

This project is suitable as an open-source demo for:

- Java backend engineering
- AI Agent orchestration
- RAG system design
- incident response workflow automation
- low-cost deployment of LLM-powered operational tools

## License

MIT