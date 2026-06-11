# Agent Run HTTP Endpoints

This page documents the HTTP surface used by the v1.0 Agent Run UI, light clients, and stress scripts.

Primary path family:

- Agent v1.0: `/api/agent/**`
- Legacy compatibility: `/api/agent-legacy/**` for most non-SSE run endpoints

Unless noted otherwise, endpoints require a logged-in user. The current backend accepts the same user identity through the JWT bearer token path and through the auth cookie set by login. JSON business responses usually use the project `ResponseWrapper` shape:

```json
{
  "code": 0,
  "data": {},
  "message": null
}
```

Non-JSON byte/download endpoints return raw bytes and headers instead of `ResponseWrapper`.

### Common HTTP status and business errors

| HTTP | `code` | Typical cause |
| --- | --- | --- |
| `200` | `0` | Success. |
| `202` | `0` | Accepted but not ready. `/result` returns this while the run is still executing with message `任务未完成`. |
| `400` | non-zero | Invalid request body, missing required field, or business pre-condition failure. |
| `401` | non-zero | Missing or invalid JWT / cookie / admin token. |
| `403` | non-zero | Insufficient permission or disabled account. |
| `404` | non-zero | Run, trace, artifact, or snapshot not found. |
| `429` | non-zero | Auth rate limit or temporary account lock. |
| `502` | non-zero | Upstream RPC failure, e.g., artifact storage unreachable. |

Most endpoints return `ResponseWrapper`; byte endpoints (artifact downloads, snapshot parts) may return the HTTP status directly with an empty body on failure.

## Auth

### `POST /api/auth/login`

Logs in and returns the raw JWT token in the response body. The same controller also sets the auth cookie.

Request:

```json
{
  "username": "string",
  "password": "string"
}
```

Success response body:

```text
eyJ...
```

Important failures:

| HTTP | Meaning |
| --- | --- |
| `400` | invalid body, missing credentials, invalid credentials, or user already logged in |
| `403` | account disabled |
| `429` | auth rate limit or temporary account lock |

The v1.0 flow client treats `400 User already logged in` as recoverable: it calls logout and retries login.

### `POST /api/auth/logout`

Logs out a username and clears the auth cookie.

Request:

```json
{
  "username": "string"
}
```

Success response body:

```text
User logged out successfully
```

## Run Lifecycle

### `POST /api/agent/runs`

Creates a new LangChain Agent Run.

Legacy equivalent: `POST /api/agent-legacy/runs`.

Request:

```json
{
  "message": "user question or instruction",
  "context": {
    "debugMode": false
  },
  "config": {
    "model": "endpointName/modelName",
    "webSearch": {
      "enabled": false,
      "sources": []
    },
    "codeInterpreter": {
      "enabled": false,
      "maxCredits": 0
    },
    "smartRetrieval": {
      "enabled": false,
      "sources": []
    }
  },
  "idempotencyKey": "optional client key",
  "modelName": "optional model override",
  "endpointName": "optional endpoint override",
  "captureLlmRequests": false,
  "provider": "optional provider",
  "plannerCandidateCount": 0,
  "debugMode": false,
  "stage_config_json": "{}"
}
```

Required field: `message`.

Notes:

- `question` is not the create field; client scripts should send `message`.
- `config.model` can also select `endpointName` and `modelName` through backend parsing.
- `plannerCandidateCount` is honored only for admin users.
- `debugMode` can be read from the top-level field or from `context.debugMode` / `context.debug_mode`.

Success response:

```json
{
  "code": 0,
  "data": {
    "id": "run-id",
    "status": "RECEIVED",
    "currentStep": 0,
    "maxSteps": 0,
    "plan": null,
    "snapshot": null,
    "lastError": null,
    "ttlExpiresAt": null,
    "startedAt": null,
    "updatedAt": null,
    "completedAt": null,
    "ext": null,
    "streamUrl": "/api/agent/runs/{runId}/stream"
  }
}
```

### `POST /api/agent/runs/{runId}:cancel`

Requests cancellation. Cancellation is asynchronous: the service writes cancel state, flushes observability where possible, appends a terminal event, and returns the current run view.

Legacy equivalent: `POST /api/agent-legacy/runs/{runId}:cancel`.

### `POST /api/agent/runs/{runId}:pause`

Pauses a run through the backend Agent service and returns the current run view.

Legacy equivalent: `POST /api/agent-legacy/runs/{runId}:pause`.

### `POST /api/agent/runs/{runId}:resume`

Resumes a run. The current request supports a plan override JSON, not a free-form `response` field.

Legacy equivalent: `POST /api/agent-legacy/runs/{runId}:resume`.

Request:

```json
{
  "planOverrideJson": "{}"
}
```

### `PUT /api/agent/runs/{runId}`

Updates the run title. The controller currently requires a non-blank `title` with length at most 120.

Legacy equivalent: `PUT /api/agent-legacy/runs/{runId}`.

Request:

```json
{
  "title": "short title"
}
```

### `DELETE /api/agent/runs/{runId}`

Deletes a run owned by the current user. Running tasks are rejected by the backend and should be cancelled or paused first.

Legacy equivalent: `DELETE /api/agent-legacy/runs/{runId}`.

Success response:

```json
{
  "code": 0,
  "data": "ok"
}
```

## Run Reads

### `GET /api/agent/runs`

Lists runs for the current user.

Legacy equivalent: `GET /api/agent-legacy/runs`.

Query parameters:

| Parameter | Default | Notes |
| --- | --- | --- |
| `limit` | backend default | preferred page size |
| `offset` | backend default | preferred offset |
| `page` | optional | converted to offset when provided |
| `size` | optional | alternate page size |
| `max` | optional | alternate page size |
| `status` | empty | backend status filter |
| `days` | `0` | recent-day filter |

Response `data`:

```json
{
  "items": [
    {
      "id": "run-id",
      "message": "original message",
      "status": "COMPLETED",
      "createdAt": "2026-06-11T12:00:00Z",
      "completedAt": "2026-06-11T12:03:00Z",
      "hasArtifacts": false,
      "durationMs": 180000,
      "totalTokens": 12345,
      "toolCalls": 6
    }
  ],
  "total": 1,
  "hasMore": false
}
```

### `GET /api/agent/runs/{runId}`

Gets the current run view.

Legacy equivalent: `GET /api/agent-legacy/runs/{runId}`.

Response `data` uses the same `AgentRunResponse` shape as create/cancel/pause/resume/update.

### `GET /api/agent/runs/{runId}/status`

Gets current status, progress, recent observability summary, and run timing fields.

Legacy equivalent: `GET /api/agent-legacy/runs/{runId}/status`.

Response `data`:

```json
{
  "id": "run-id",
  "status": "EXECUTING",
  "phase": "execution",
  "currentTool": null,
  "lastEventType": "TOOL_CALL_FINISHED",
  "lastEventAt": "2026-06-11T12:00:30Z",
  "lastEventPayload": {},
  "plan": {},
  "progress": {},
  "observability": {},
  "observabilitySummary": {},
  "observabilityFullAvailable": true,
  "totalCreditsConsumed": 0,
  "eventCount": 42,
  "startedAtMs": 1781160000000,
  "completedAtMs": null,
  "elapsedMs": 30000
}
```

Common statuses include `RECEIVED`, `PLANNING`, `EXECUTING`, `WAITING`, `SUMMARIZING`, `COMPLETED`, `PARTIAL`, `FAILED`, `CANCELING`, `CANCELED`, and `EXPIRED`. SSE terminal detection also accepts `CANCELLED`, `TIMEOUT`, and `TIMED_OUT`.

### `GET /api/agent/runs/{runId}/result`

Gets the final answer payload. If the run is not `COMPLETED`, the controller returns HTTP `202` with a successful wrapper and message `任务未完成`.

Legacy equivalent: `GET /api/agent-legacy/runs/{runId}/result`.

Response `data`:

```json
{
  "id": "run-id",
  "status": "COMPLETED",
  "answer": "plain answer",
  "answerMarkdown": "markdown answer",
  "structuredAnswer": {},
  "payload": {},
  "observability": null,
  "totalCreditsConsumed": 0
}
```

### `GET /api/agent/runs/{runId}/cost`

Gets persisted or computed cost data.

Legacy equivalent: `GET /api/agent-legacy/runs/{runId}/cost`.

Response `data`:

```json
{
  "id": "run-id",
  "totalCost": 0.0123,
  "upstreamInferenceCost": 0.015,
  "cacheDiscount": 0.0027,
  "costedCallCount": 3,
  "totalCallCount": 3,
  "complete": true,
  "currency": "USD",
  "source": "openrouter",
  "updatedAt": "2026-06-11T12:03:00Z",
  "persisted": true,
  "calls": [
    {
      "traceId": "trace-id",
      "generationId": "generation-id",
      "phase": "planning",
      "todoId": null,
      "endpoint": "endpoint",
      "model": "model",
      "actualCost": 0.0123,
      "upstreamInferenceCost": 0.015,
      "cacheDiscount": 0.0027,
      "isByok": false,
      "startedAtMs": 1781160000000,
      "completedAtMs": 1781160001000,
      "source": "openrouter"
    }
  ]
}
```

Cost can lag run completion because total run cost is persisted after observability data is available.

Field semantics:

| Field | Meaning |
| --- | --- |
| `totalCost` | Best-effort total cost in `currency`. May be `0` or `null` while `complete=false`. |
| `upstreamInferenceCost` | Pre-discount inference cost reported by the provider. |
| `cacheDiscount` | Discount applied for cached tokens, if provider reports it. |
| `costedCallCount` | Number of calls that contributed to the total cost. |
| `totalCallCount` | Total LLM calls observed; may be larger than `costedCallCount` when provider pricing is missing. |
| `complete` | `true` when cost has been fully reconciled from observability; `false` when still computing. |
| `persisted` | `true` when the run-level total has been written to persistent storage. |
| `calls` | Per-call cost entries; omitted or empty when no calls are costed. |

## SSE Stream

### `GET /api/agent/runs/{runId}/stream`

Opens the v1.0 SSE stream. There is no legacy SSE stream endpoint.

Query/header resume controls:

| Name | Location | Notes |
| --- | --- | --- |
| `after_seq` | query | preferred replay cursor; sends events with `seq > after_seq` |
| `Last-Event-ID` | header | fallback replay cursor when `after_seq` is absent |

The controller advertises cookie or bearer-header auth. In practice, ordinary browser `EventSource` should use the auth cookie; Python/agent clients may use a requests-based SSE client with `Authorization: Bearer <token>`.

SSE event names:

| Event | Payload summary |
| --- | --- |
| `snapshot` | first packet on fresh connection; includes status, phase, plan, latest events, and `lastSeq` |
| `agent.event` | durable Agent event from DB/Redis with `eventType` and normalized object `payload` |
| `run.status` | status/phase watcher event, emitted initially and when changed |
| `heartbeat` | keepalive every 30 seconds |
| `run.done` | terminal status notification |
| `error` | stream init/auth/replay error, then stream closes |

SSE wire envelope:

```text
id: 42
event: agent.event
data: {"type":"agent.event","runId":"run-id","seq":42,"eventType":"TOOL_CALL_FINISHED","payload":{},"ts":1781160030000}
```

- `id` is the monotonic sequence number for this event. Clients reconnecting with `Last-Event-ID` receive events with `id > Last-Event-ID`.
- `event` is the SSE event name listed above.
- `data` is a single-line JSON object. Multi-line payloads are split into multiple `data:` lines; clients must join them before parsing JSON.
- Blank lines between events terminate the previous event.

Fresh connection snapshot:

```json
{
  "type": "snapshot",
  "runId": "run-id",
  "ts": 1781160000000,
  "status": "EXECUTING",
  "phase": "execution",
  "startedAtMs": 1781160000000,
  "completedAtMs": null,
  "plan": {},
  "events": [],
  "eventCount": 0,
  "lastSeq": 0
}
```

`agent.event` payload:

```json
{
  "type": "agent.event",
  "runId": "run-id",
  "seq": 42,
  "eventType": "TOOL_CALL_FINISHED",
  "payload": {
    "toolName": "ragSearch",
    "success": true
  },
  "createdAt": "2026-06-11T12:00:30Z",
  "ts": 1781160030000
}
```

Implementation boundaries:

- Fresh connections receive snapshot first; reconnects with a cursor replay durable events first.
- Replay reads DB events in pages of 200 and buffers concurrent live Redis messages up to 500 entries.
- If the live buffer overflows during replay, the stream emits `LIVE_REPLAY_BUFFER_OVERFLOW` and clients should repair with REST `/events`.
- Live events are emitted with SSE event name `agent.event`; the nested business type is `eventType`.
- `payloadJson` is normalized to a JSON object before leaving the SSE service.

## Events And Timeline

### `GET /api/agent/runs/{runId}/events`

Lists durable Agent events ordered by sequence.

Legacy equivalent: `GET /api/agent-legacy/runs/{runId}/events`.

Query parameters:

| Parameter | Default | Notes |
| --- | --- | --- |
| `after_seq` | `0` | returns events with `seq > after_seq` |
| `limit` | `200` | clamped to `1..500` |

Response `data`:

```json
{
  "items": [
    {
      "id": "event-id",
      "runId": "run-id",
      "seq": 42,
      "eventType": "TOOL_CALL_FINISHED",
      "payload": {},
      "createdAt": "2026-06-11T12:00:30Z"
    }
  ],
  "nextAfterSeq": 42,
  "hasMore": false
}
```

### `GET /api/agent/runs/{runId}/timeline`

Builds a timeline by combining recent events and observability spans.

Legacy equivalent: `GET /api/agent-legacy/runs/{runId}/timeline`.

Query parameters:

| Parameter | Default | Notes |
| --- | --- | --- |
| `after_seq` | `0` | event cursor |
| `limit` | `100` | clamped to `1..500` |

Response `data`:

```json
{
  "items": [
    {
      "seq": 42,
      "source": "event",
      "traceId": null,
      "type": "TOOL_CALL_FINISHED",
      "time": "2026-06-11T12:00:30Z",
      "title": "tool call finished",
      "durationMs": null,
      "detail": {}
    }
  ],
  "nextAfterSeq": 42,
  "hasMore": false
}
```

Pagination: items are sorted by `seq`. Use `nextAfterSeq` as the `after_seq` parameter on the next request. Stop when `hasMore=false`.

## Observability

### `GET /api/agent/runs/{runId}/observability/full`

Returns full observability JSON from the run result when available.

Legacy equivalent: `GET /api/agent-legacy/runs/{runId}/observability/full`.

Boundary:

- Responses over 5 MiB are rejected with a business error telling clients to use `/traces` or `/timeline`.
- If observability JSON is absent or unparsable, the endpoint returns `DATA_NOT_FOUND`.

### `GET /api/agent/runs/{runId}/traces`

Lists normalized LLM and tool spans from observability diagnostics.

Legacy equivalent: `GET /api/agent-legacy/runs/{runId}/traces`.

Query parameters:

| Parameter | Default | Notes |
| --- | --- | --- |
| `type` | empty | optional `llm` or `tool` filter |
| `phase` | empty | optional phase filter |
| `after` | `0` | synthetic span sequence cursor |
| `limit` | `100` | clamped to `1..500` |

Response `data`:

```json
{
  "spans": [
    {
      "seq": 1,
      "type": "llm",
      "traceId": "trace-id",
      "time": "2026-06-11T12:00:00Z",
      "phase": "planning",
      "todoId": null,
      "durationMs": 15234,
      "model": "model",
      "inputTokens": 1000,
      "outputTokens": 500,
      "hasError": false,
      "hasInputMessages": true,
      "hasReasoning": false,
      "outputSummary": "..."
    }
  ],
  "summary": {
    "totalLlmCalls": 1,
    "totalToolCalls": 0,
    "totalDurationMs": 15234,
    "totalTokens": 1500
  }
}
```

Pagination and filtering:

- `type` and `phase` filters are combined with AND.
- `after` is a synthetic span sequence cursor. The endpoint assigns a monotonic `seq` to each span and returns spans with `seq > after`.
- The response does **not** include a `nextAfter` cursor. Clients that need pagination should track the largest `seq` returned and pass it as `after` on the next request, or fall back to `/timeline` which exposes `nextAfterSeq`.
- `summary` reflects the spans selected by the current query, not the entire run.

### `GET /api/agent/runs/{runId}/traces/{traceId}`

Returns a single LLM or tool trace from observability JSON.

Legacy equivalent: `GET /api/agent-legacy/runs/{runId}/traces/{traceId}`.

LLM details can include model, endpoint, token counts, cost, input messages, output text, reasoning text, errors, attempts, HTTP request/response, and curl command. Tool details can include tool name, params, output, success, cache state, decision trace, and decision excerpt.

### `GET /api/agent/runs/{runId}/llm-calls/{llmCallId}/detail`

Returns safe lazy-loaded LLM call detail. There is no legacy mapping for this endpoint.

Query parameters:

| Parameter | Default | Notes |
| --- | --- | --- |
| `includeThinking` | `false` | honored only for admin users; non-admin callers are silently treated as `false` |

Response fields include `detailKind`, `source`, `summary`, `metrics`, `llm`, `limits`, and optional `reasoningUnavailable`.

When `includeThinking=true` and the caller is admin, the response may include reasoning/thinking text if the provider returned it. Non-admin callers never receive thinking content through this endpoint.

### `GET /api/agent/runs/{runId}/tool-calls/{toolCallId}/detail`

Returns safe lazy-loaded tool call detail. There is no legacy mapping for this endpoint.

Response fields include `detailKind`, `source`, `summary`, `metrics`, `tool`, and `limits`.

## Snapshot Parts

### `GET /api/agent/runs/{runId}/snapshot/parts`

Returns metadata for downloading large run snapshots by part.

Legacy equivalent: `GET /api/agent-legacy/runs/{runId}/snapshot/parts`.

Query parameters:

| Parameter | Default | Notes |
| --- | --- | --- |
| `maxPartSize` | `0` | backend-selected part size when `0` |

Response `data`:

```json
{
  "runId": "run-id",
  "partSize": 262144,
  "totalParts": 3,
  "uncompressedSize": 700000,
  "compressedSize": 500000,
  "compression": "gzip",
  "checksum": "sha256:..."
}
```

### `GET /api/agent/runs/{runId}/snapshot/parts/{partIndex}`

Downloads one snapshot part as bytes.

Legacy equivalent: `GET /api/agent-legacy/runs/{runId}/snapshot/parts/{partIndex}`.

Response headers:

| Header | Meaning |
| --- | --- |
| `Content-Type` | `application/octet-stream` |
| `Cache-Control` | `no-store` |
| `X-Snapshot-Compression` | compression algorithm |
| `X-Snapshot-Part-Index` | current part index |
| `X-Snapshot-Part-Size` | current part size |
| `X-Snapshot-Total-Parts` | total part count |

## Artifacts

### `GET /api/agent/runs/{runId}/artifacts`

Lists artifacts for a run.

Legacy equivalent: `GET /api/agent-legacy/runs/{runId}/artifacts`.

Response `data`:

```json
[
  {
    "artifactId": "artifact-id",
    "type": "file",
    "name": "output.csv",
    "contentType": "text/csv",
    "url": "/api/agent/runs/{runId}/artifacts/{artifactId}/download",
    "metaJson": "{}",
    "createdAt": "2026-06-11T12:03:00Z",
    "expiresAtMillis": 1781163600000
  }
]
```

### `GET /api/agent/runs/{runId}/artifacts/{artifactId}/download`

Downloads an artifact as bytes.

Legacy equivalent: `GET /api/agent-legacy/runs/{runId}/artifacts/{artifactId}/download`.

Important failures:

| HTTP | Meaning |
| --- | --- |
| `401` | unauthenticated |
| `404` | artifact or run not found |
| `422` | artifact too large |
| `502` | upstream RPC failure |

## Feedback And Export

### `POST /api/agent/runs/{runId}/feedback`

Submits feedback for a run.

Legacy equivalent: `POST /api/agent-legacy/runs/{runId}/feedback`.

Request:

```json
{
  "rating": 1,
  "comment": "optional comment",
  "tags": ["useful"],
  "payload": {
    "source": "light-client"
  }
}
```

Current `rating` is an integer. Do not send `"positive"` / `"negative"` strings to this controller.

### `POST /api/agent/runs/{runId}:export`

Starts or performs run export.

Legacy equivalent: `POST /api/agent-legacy/runs/{runId}:export`.

Request:

```json
{
  "format": "json"
}
```

Response `data`:

```json
{
  "exportId": "export-id",
  "status": "READY",
  "url": "/api/agent/runs/{runId}/artifacts/{artifactId}/download",
  "message": null
}
```

## Follow-Up Messages

### `POST /api/agent/runs/{runId}/messages`

Sends a follow-up message to a completed run.

Legacy equivalent: `POST /api/agent-legacy/runs/{runId}/messages`.

Request:

```json
{
  "content": "follow-up question",
  "contextOverride": "{}",
  "debugMode": false,
  "stream": false
}
```

Boundary:

- `content` is required.
- `contextOverride` is honored only when the caller is admin and `debugMode=true`.
- `stream` is accepted by the request model and passed to the backend; clients should still rely on the run SSE stream for event updates.

Response `data`:

```json
{
  "messageId": "message-id",
  "seq": 2,
  "status": "ACCEPTED",
  "runStatus": "EXECUTING",
  "rejectReason": null
}
```

### `GET /api/agent/runs/{runId}/messages`

Lists run messages.

Legacy equivalent: `GET /api/agent-legacy/runs/{runId}/messages`.

Query parameters:

| Parameter | Default | Notes |
| --- | --- | --- |
| `limit` | `50` | clamped to `1..200` |
| `offset` | `0` | non-negative offset |
| `include_initial` | `true` | include initial user message |

Response `data`:

```json
{
  "items": [
    {
      "id": "message-id",
      "seq": 1,
      "role": "user",
      "content": "question",
      "msgType": "initial",
      "metaJson": "{}",
      "createdAt": "2026-06-11T12:00:00Z"
    }
  ],
  "total": 1,
  "hasMore": false
}
```

## Stress-Script Coverage

`test_scripts/agent-v1p0/agent_run_sse_load_test.py` uses a smaller subset directly:

| Endpoint | Script usage |
| --- | --- |
| `POST /api/auth/login` | `FlowClient.login()` or configured `auth.login_endpoint` |
| `POST /api/auth/logout` | configured cleanup endpoint |
| `POST /api/agent/runs` | run creation through the orchestrator |
| `GET /api/agent/runs/{runId}/stream` | primary live event source |
| `POST /api/agent/runs/{runId}:cancel` | stop/cancel flow |
| `GET /api/agent/runs/{runId}/status` | fallback/status reconciliation |
| `GET /api/agent/runs/{runId}/result` | final content fetch |
| `GET /api/agent/runs/{runId}/events` | gap-fill via `after_seq` when SSE lags (`orchestrator._repair_events_from_rest`) |
| `GET /api/agent/runs/{runId}/cost` | cost fetch in related flow scripts |
| `GET /api/agent/runs/{runId}/snapshot/parts` | snapshot part metadata in reusable flow helpers |
| `GET /api/agent/runs/{runId}/snapshot/parts/{partIndex}` | snapshot part download in reusable flow helpers |

The load test treats SSE as the primary source of progress and avoids post-run observability polling by default. Human debugging tools can use the broader observability endpoints above.
