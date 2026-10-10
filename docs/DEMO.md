# Demo script (5 minutes)

A script for showing LoomVault to someone — a reviewer, an interviewer, a teammate. Each step
produces a number or a log line you can point at. Everything runs against the bundled offline mock
provider, so there are no API keys and no cost.

```bash
cd deploy && docker compose up --build
```

Wait for `LoomVault is ready.` then open <http://localhost:8080>. Set `BASE=http://localhost:8080`
and `JQ='jq -r'` for the commands below.

---

## 0. The control room (30 s)

Open the UI. On a fresh database the startup runner has already:

- registered three workflows (`support-triage`, `content-review`, `reliability-drill`)
- started one demo run, which is now sitting in **needs approval: approve_reply**

Point out the metric strip: tokens, cost, budget pressure, model calls vs cache hits. The graph on
the left shows which nodes are green (done), which are amber (waiting), and which are grey
(not reached yet).

> **Line to use:** "This run is parked, not blocked. The container can be killed right now and
> nothing is lost."

---

## 1. A run that pauses for a human (60 s)

```bash
RUN=$(curl -s -X POST "$BASE/api/executions?wait=true" \
  -H 'Content-Type: application/json' \
  -d '{"workflowId":"support-triage","inputs":{"ticketId":"TCK-1002"}}' | jq -r .id)

curl -s "$BASE/api/executions/$RUN" | jq '{status, pendingApprovals, nodes: [.nodes[] | {nodeId, status}]}'
```

Expected:

```
status: WAITING_APPROVAL
pendingApprovals: ["approve_reply"]
8 nodes, 5 COMPLETED, 1 WAITING_APPROVAL, 2 PENDING
```

Note that `notify_customer` and `record_outcome` are still `PENDING` — side effects have not fired
yet. That is the point of the approval gate.

```bash
curl -s -X POST "$BASE/api/executions/$RUN/approve" \
  -H 'Content-Type: application/json' \
  -d '{"approver":"demo","comment":"approved in the demo"}' | jq '{status, resumeCount, usage}'
```

Expected: `"status": "SUCCEEDED"`, `"resumeCount": 1`. Watch the UI: the two tool nodes light up.

Prove the side effects are real and ordered:

```bash
curl -s "$BASE/api/system/effects" | jq '{notifications: (.notifications|length), outcomes: (.outcomes|length)}'
```

---

## 2. The journal, and why it exists (45 s)

```bash
curl -s "$BASE/api/executions/$RUN/journal" \
  | jq -r '.[] | "\(.seq)\t\(.type)\t\(.nodeId // "-")"'
```

Walk down the list. The story of the run is readable without any code:

```
1   EXECUTION_STARTED     -
3   NODE_STARTED          fetch_ticket
4   NODE_COMPLETED        fetch_ticket
...
12  APPROVAL_REQUESTED    approve_reply      ← the run parks here
13  APPROVAL_GRANTED      approve_reply
14  EXECUTION_RESUMED     -                  replayedEntries=12 restored=5
...
```

> **Line to use:** "The resume entry says it replayed twelve entries and restored five nodes. Those
> five were never re-executed — their outputs came straight out of this log."

---

## 3. Conditional routing (45 s)

```bash
curl -s -X POST "$BASE/api/executions?wait=true" -H 'Content-Type: application/json' \
  -d '{"workflowId":"content-review","inputs":{"draft":"LoomVault journals every step, enforces budgets, and parks runs for approval."}}' \
  | jq '{status, nodes: [.nodes[] | {nodeId, status}]}'
```

Expected: the three reviewers run in parallel (check `durationMs` — they overlap), then exactly one
of `publish_ready` / `needs_revision` is `COMPLETED` and the other is `SKIPPED`.

> **Line to use:** "There is no router node. Routing is a condition on the edge, evaluated by the
> same expression engine that renders the prompts."

---

## 4. Caching: the same input twice (30 s)

```bash
BODY='{"workflowId":"content-review","inputs":{"draft":"identical input for the cache demo"}}'

curl -s -X POST "$BASE/api/executions?wait=true" -H 'Content-Type: application/json' -d "$BODY" | jq .usage
curl -s -X POST "$BASE/api/executions?wait=true" -H 'Content-Type: application/json' -d "$BODY" | jq .usage
```

```
run 1: {"totalTokens": 1180, "llmCalls": 5, "cacheHits": 0, "costUsd": 0.00024}
run 2: {"totalTokens": 0,    "llmCalls": 0, "cacheHits": 5, "costUsd": 0}
```

> **Line to use:** "The second run is free. Cache key is a SHA-256 of model, temperature and
> messages — not a hash of 'roughly the same question'."

---

## 5. Retries and the budget guard (60 s)

Restart with hostile settings:

```bash
docker compose down
LOOMVAULT_MOCK_FAIL_FIRST_CALLS=2 docker compose up
```

*(or edit `loomvault.llm.mock.fail-first-calls: 2` in `application.yml` and rebuild)*

```bash
curl -s -X POST "$BASE/api/executions?wait=true" -H 'Content-Type: application/json' \
  -d '{"workflowId":"reliability-drill","inputs":{"subject":"Q3 platform review"}}' \
  | jq '{status, usage}'

curl -s "$BASE/api/executions/<id>/journal" | jq -r '.[] | select(.type|test("RETRY|FAILED")) | "\(.seq) \(.type) \(.payload)"'
```

Expect `NODE_FAILED` → `NODE_RETRY_SCHEDULED` (with `delayMs`) → `NODE_STARTED` (attempt 2), then
completion. The workflow's budget is 900 tokens across four parallel frontier-model calls, so the
run crosses it: look for `BUDGET_EXCEEDED` with `"action": "continuing under DEGRADE"`.

> **Line to use:** "Same workflow, two guardrails: transient failures retry with jittered backoff,
> and cost pressure degrades the model instead of killing the run."

---

## 6. Crash recovery (90 s) — the headline

The most impressive step, and the easiest to get wrong on stage. Use a file-backed database so
state survives the kill:

```bash
docker compose down
docker compose run --rm -p 8080:8080 \
  -e LOOMVAULT_DB_URL='jdbc:h2:file:/data/loomvault' \
  -e LOOMVAULT_MOCK_MIN_LATENCY=3s -e LOOMVAULT_MOCK_MAX_LATENCY=3s \
  loomvault
```

In a second terminal, start the four-way fan-out and kill the container mid-flight:

```bash
curl -s -X POST "$BASE/api/executions" -H 'Content-Type: application/json' \
  -d '{"workflowId":"reliability-drill","inputs":{"subject":"crash demo"}}'

sleep 4 && curl -s "$BASE/api/executions?limit=1" | jq -r '.[0] | "\(.id) \(.status)"'
# → RUNNING, with 4 angle_* nodes COMPLETED and synthesis RUNNING

docker kill loomvault          # simulate the pod being evicted
docker compose up loomvault    # bring it back
```

Watch the log:

```
execution.recovery.non_idempotent id=exe_... node=synthesis
execution.resumed                 id=exe_... replayedEntries=15 restored=4
execution.succeeded               id=exe_... nodes=5 tokens=1148
execution.recovery.completed      count=1
```

Then the proof:

```bash
curl -s "$BASE/api/executions/<id>" | jq '[.nodes[] | {nodeId, status, durationMs}]'
```

The four `angle_*` nodes still show their **original ~3000 ms** durations — they were restored from
the journal, not re-run. Only `synthesis` shows a fresh, short duration.

> **Line to use:** "Four model calls that already succeeded were not paid for twice. The one that
> was interrupted in flight was re-executed, and the engine said so in the journal rather than
> pretending it knew what happened."

---

## 7. Closing points

- `GET /actuator/prometheus` — the metrics a Grafana dashboard would scrape.
- `POST /api/workflows/validate` — upload a workflow with a deliberate cycle and watch it come back
  with the exact offending path instead of a stack trace.
- Point at `loomvault-core`: no Spring, no JDBC. The engine is a library; the server is a shell.

### Reset between demos

```bash
docker compose down -v && docker compose up --build
```

`-v` drops the PostgreSQL volume, so the next startup seeds a fresh demo run.
