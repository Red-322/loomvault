<div align="center">

**English** · [简体中文](README.zh-CN.md)

# LoomVault

**Durable execution for AI agent workflows — replayable, resumable, budget-aware.**

[![CI](https://img.shields.io/badge/CI-GitHub_Actions-2088FF?logo=githubactions&logoColor=white)](.github/workflows/ci.yml)
[![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Tests](https://img.shields.io/badge/tests-132_passing-brightgreen.svg)](#testing)

*If your agent workflow dies halfway through, you should not have to pay for the first half twice.*

[Quick start](#quick-start) · [Why](#why-another-agent-framework) · [How it works](#how-it-works) · [API](#api) · [Roadmap](#roadmap)

</div>

---

## The problem

Every team building LLM-powered automation hits the same wall, in the same order:

1. **A step fails at minute nine of a ten-minute run.** The naive answer is "retry the whole
   workflow" — which re-bills every model call that already succeeded, and re-fires every side
   effect that already happened. Sending the customer notification twice is a real incident.
2. **An agent burns $40 of tokens at 3am.** Nobody notices until the invoice. "Set a spend limit"
   is easy to say and, in most frameworks, impossible to enforce per workflow.
3. **You cannot reproduce yesterday's run.** The model was upgraded, the prompt was edited, a
   tool changed behaviour. The transcript is gone, so the post-mortem is guesswork.
4. **A human needs to approve something.** So the workflow becomes a `Thread.sleep` in a servlet,
   or an email thread, or ... nothing.

Existing Python agent frameworks optimise for *expressing* an agent. LoomVault optimises for
**running one in production**: every step is journalled, every restart resumes instead of
repeating, every token is accounted against a declared budget, and any run can be replayed from
its own log.

## What you get

| | |
|---|---|
| **Deterministic replay** | Node outputs are recorded in an append-only journal. A restarted run restores completed nodes from the log and *does not re-execute them* — so successful model calls are never billed twice and committed side effects never repeat. |
| **Crash recovery in one line** | On startup the engine finds executions left `RUNNING` by a dead process and resumes them. Verified in the demo: 4 parallel nodes kept their original timings, only the interrupted node re-ran. |
| **Budget as a first-class citizen** | Token, cost, call-count and wall-clock limits at workflow *and* node level, with four strategies — `FAIL`, `DEGRADE` (fall back to a cheaper model), `TRUNCATE`, `WARN`. Budgets survive restarts, so a crash loop cannot outspend a limit. |
| **Human-in-the-loop that survives a restart** | An approval node parks the whole execution — state persisted, container free to die. A human approves hours later and the run continues from exactly where it stopped. |
| **Content-addressed response cache** | Cache key = SHA-256 of model + temperature + messages. Re-running the same input serves every call from cache at **zero tokens and zero dollars** (measured: 941 tokens → 0). |
| **Conditional DAG, not a chain** | Parallel fan-out, fan-in with merged context, conditional edges, and skip-propagation down branches that were not taken. Scheduling on **Java 21 virtual threads**. |
| **Runs with no API key** | A deterministic mock provider is the default, so `docker compose up` produces a complete, inspectable execution in seconds. Point it at OpenAI/DeepSeek/Qwen/Ollama with one config value. |

> **Design stance:** LoomVault is not a prompt-building library. It is the layer *underneath* one —
> the part that decides when a step runs, what it is allowed to cost, what happens when the
> process dies, and how you prove afterwards that it did what it claimed.

## Quick start

### Docker (PostgreSQL + Redis + app)

```bash
git clone https://github.com/<your-account>/loomvault.git
cd loomvault/deploy
docker compose up --build
```

Open **http://localhost:8080** — three example workflows are registered and one demo run is
already waiting for your approval. No API key, no configuration.

### Local, zero dependencies

```bash
./mvnw spring-boot:run -pl loomvault-server -Dspring-boot.run.profiles=standalone
```

Uses an in-memory H2 database and a process-local cache. The full JDBC persistence path is still
exercised; only the database engine differs. Requires **JDK 21**.

### Five commands that show the whole story

```bash
# 1. Run a workflow end to end
curl -s -X POST 'localhost:8080/api/executions?wait=true' \
  -H 'Content-Type: application/json' \
  -d '{"workflowId":"support-triage","inputs":{"ticketId":"TCK-1001"}}' | jq '.status'
# → "WAITING_APPROVAL"   the run parked itself for a human

# 2. Approve it — the run continues from exactly where it stopped
curl -s -X POST localhost:8080/api/executions/<id>/approve \
  -H 'Content-Type: application/json' -d '{"approver":"you"}' | jq '.status'
# → "SUCCEEDED"

# 3. Read the journal that makes all of the above auditable
curl -s localhost:8080/api/executions/<id>/journal | jq '.[] | {seq,type,nodeId}'

# 4. Re-run the same input: every model call is served from cache, for free
curl -s -X POST 'localhost:8080/api/executions?wait=true' \
  -H 'Content-Type: application/json' \
  -d '{"workflowId":"content-review","inputs":{"draft":"..."}}' | jq '.usage'
# second identical run → {"totalTokens":0,"llmCalls":0,"cacheHits":5,"costUsd":0}

# 5. Crash it on purpose, then watch it heal
#    start a run, `docker compose kill loomvault` mid-flight, `docker compose up` again —
#    the startup recovery resumes it and the logs show: replayedEntries=15 restored=4
```

Want to *watch* the guardrails fire? Set `loomvault.llm.mock.fail-first-calls=2` and run
`reliability-drill`: you get real retries with backoff in the journal, and a deliberately tight
900-token budget that forces the `DEGRADE` strategy.

## Why another agent framework?

Fair question. Here is the honest comparison.

**vs. Python agent frameworks (LangChain / LangGraph / CrewAI)**
They are excellent at describing an agent and have far richer model integrations. They are also
Python-first, in-process, and — for the durable-execution part — checkpoint to a blob whose
semantics you have to reason about yourself. If your stack is Java and your requirement is
"survive a pod restart without double-charging", you are writing that layer anyway.

**vs. Temporal / Cadence**
Temporal is a superb general-purpose durable execution engine and LoomVault borrows its central
idea. But it was not designed for the things that make agents different: token/cost budgets as a
first-class limit, content-addressed model caching, model fallback on budget pressure, or
approval nodes that a non-engineer can act on. It also asks you to run a cluster. LoomVault is
one Spring Boot jar and two containers, and its cost model is a first-class concept rather than
something you build on top.

**vs. workflow builders (Dify / n8n / Flowise)**
Those are low-code canvases. LoomVault has no canvas for *authoring* — workflows are YAML in your
repository, reviewed in pull requests, with a graph you can unit-test. The bundled UI is for
*operating* runs, not for drag-and-drop design.

**The gap it fills:** a Java-native execution kernel where **durability, cost governance and
human approval are the same feature**, not three integrations.

## How it works

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│  loomvault-server          REST · SSE · control-room UI · Micrometer · recovery   │
├──────────────────────────────────────────────────────────────────────────────────┤
│  loomvault-core            DAG scheduler · journal · replay · budget · expressions │
│                            (no Spring, no JDBC — embeddable and unit-testable)     │
├───────────────────────────────┬──────────────────────────────────────────────────┤
│  loomvault-llm                │  loomvault-store                                 │
│  mock · openai-compatible     │  JDBC (PostgreSQL/H2) · in-memory                │
│  cache · circuit breaker      │  Flyway migrations                               │
└───────────────────────────────┴──────────────────────────────────────────────────┘
```

### Durable execution, concretely

```
   ┌─ journal (append-only, the source of truth) ─────────────────────────────┐
   │  #1 EXECUTION_STARTED        #6 NODE_COMPLETED  classify   {category:...}│
   │  #2 NODE_SCHEDULED fetch     #7 NODE_STARTED    score_risk               │
   │  #3 NODE_STARTED   fetch     #8 NODE_COMPLETED  score_risk {overall:...} │
   │  #4 NODE_COMPLETED fetch     ...                                          │
   │  #5 NODE_COMPLETED load_...  #15 APPROVAL_REQUESTED approve_reply        │
   └──────────────────────────────────────────────────────────────────────────┘
                                      │
                     process dies here ┘
                                      ▼
   on restart:  replay entries #1..#15
                ├─ completed nodes  → restored from their recorded outputs (no model calls)
                ├─ interrupted node → re-executed, with a RECOVERY_WARNING if non-idempotent
                └─ budget ledger    → re-hydrated with the tokens already spent
```

The scheduler is a single thread that owns all mutable state (ready queue, predecessor counters,
in-flight map). Workers run handlers on **immutable context snapshots** and post results back
through one queue. There is no lock ordering to get wrong; parallelism comes from virtual threads
fanning out the IO-bound model calls, capped by `max-parallel-nodes` so a fan-out of eight nodes
cannot turn a provider blip into a retry storm.

Full design notes: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

### Cost governance

Budgets are declared in the workflow definition and enforced by the scheduler between
completions:

```yaml
budget:
  maxTotalTokens: 24000
  maxLlmCalls: 12
  maxCostUsd: "0.75"
  onExceed: DEGRADE        # FAIL | DEGRADE | TRUNCATE | WARN
```

`DEGRADE` makes handlers fall back to a node's `fallbackModel`; `FAIL` stops the run and marks it
`BUDGET_EXCEEDED`. Because the ledger is rebuilt from the journal on resume, the limit counts
spend across restarts — a crash loop cannot outspend it by dying often enough.

### The expression engine

Edge conditions and prompt templates share one small, hand-written language — no SpEL, no
JavaScript, no expression-injection surface:

```yaml
edges:
  - from: aggregate
    to: publish_ready
    condition: nodes.aggregate.pass == true && nodes.aggregate.overall >= 60
```

Evaluation is **total**: missing paths and division by zero yield `null`, `null` is false in
boolean position, so a condition never explodes mid-run. Unknown *functions* are rejected at
parse time, so a typo fails when the workflow is loaded, not at 3am.

## Modules

| Module | Contents | Depends on |
|---|---|---|
| `loomvault-core` | Domain model, DAG scheduler, journal/replay, budget ledger, expression engine, retry, ports | Jackson, SLF4J only |
| `loomvault-llm` | Deterministic mock provider, OpenAI-compatible client, content-addressed cache, circuit breaker, resilience decorators | core |
| `loomvault-store` | JDBC and in-memory repositories, journal store, Flyway migrations (PostgreSQL + H2) | core, spring-jdbc, Flyway |
| `loomvault-server` | REST API, SSE, observability, startup recovery, bundled control room | all of the above, Spring Boot |

`loomvault-core` has no Spring dependency. That is what lets the engine be tested end to end in
milliseconds and embedded in anything.

## API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/executions` | Start a run (`?wait=true` to block). Idempotent when you supply `executionId`. |
| `GET` | `/api/executions` | Recent runs |
| `GET` | `/api/executions/{id}` | Full run: status, usage, per-node state |
| `GET` | `/api/executions/{id}/journal` | The append-only journal |
| `GET` | `/api/executions/{id}/events` | **SSE** live event stream |
| `POST` | `/api/executions/{id}/approve` · `/reject` | Decide a parked approval node |
| `POST` | `/api/executions/{id}/resume` · `/cancel` | Manual recovery / cancellation |
| `GET` `POST` `DELETE` | `/api/workflows[/{id}]` | Catalogue (validated on registration) |
| `POST` | `/api/workflows/validate` | Lint a definition without deploying it |
| `GET` | `/api/system/{info,stats,tools,models,effects,live}` | Introspection |
| `GET` | `/actuator/prometheus` | Metrics for Grafana |

Errors are uniform: a stable `code` (`GRAPH_CYCLE_DETECTED`, `BUDGET_EXCEEDED`,
`LLM_CIRCUIT_OPEN`, …), a `traceId` echoed in the `X-Trace-Id` header, and a `context` map that
often tells you exactly which node is at fault.

## Observability

Every log line carries `traceId` (from the HTTP request) and `executionId` (from the engine), so
one grep reconstructs a whole run. Metrics are derived from the event stream rather than sprinkled
through the scheduler:

`loomvault_executions_finished_total{status}` · `loomvault_nodes_settled_total{result}` ·
`loomvault_llm_tokens_total{kind}` · `loomvault_llm_cache_total{result}` ·
`loomvault_llm_cost_usd_total` · `loomvault_budget_events_total{type}` · `loomvault_retries_total`

## Configuration

Everything lives under `loomvault.*` in `application.yml`. The knobs that matter most:

| Key | Default | Notes |
|---|---|---|
| `loomvault.engine.max-parallel-nodes` | `4` | In-flight cap. The single most effective protection for your provider quota. |
| `loomvault.llm.provider` | `mock` | `mock` or `openai` (any `/v1/chat/completions` endpoint: OpenAI, DeepSeek, Qwen, Ollama, vLLM). |
| `loomvault.llm.openai.base-url` / `api-key` | — | Or set `LOOMVAULT_LLM_OPENAI_BASE_URL` / `LOOMVAULT_LLM_OPENAI_API_KEY`. |
| `loomvault.cache.mode` | `redis` | Use `memory` for single-node. Falls back to memory with a warning if Redis is unreachable. |
| `loomvault.engine.recover-on-startup` | `true` | Resume executions left `RUNNING` by a dead process. |
| `loomvault.pricing.overrides.<model>` | — | USD per million tokens. Unknown models are priced at **zero**, never guessed. |

Using a real model:

```bash
LOOMVAULT_LLM_PROVIDER=openai \
LOOMVAULT_LLM_OPENAI_BASE_URL=https://api.deepseek.com/v1 \
LOOMVAULT_LLM_OPENAI_API_KEY=sk-... \
docker compose up
```

## Testing

```bash
./mvnw verify        # 132 tests
```

Coverage focuses on the properties that are expensive to get wrong: three-valued expression
semantics, cycle detection with path reporting, budget threshold arithmetic, LRU/TTL cache
eviction, circuit-breaker state transitions, and end-to-end engine behaviour — including **"a
replayed node does not re-execute"**, which is the guarantee the whole design exists to provide.

## Roadmap

- [ ] **Sub-workflows** — a node kind that runs another workflow, with parent/child journal linking
- [ ] **Scheduled and event triggers** — cron and webhook entry points, not just `POST`
- [ ] **Parallel-for over collections** — dynamic fan-out with a runtime-determined width
- [ ] **Provider streaming** — token-by-token SSE for long generations, journalled incrementally
- [ ] **OpenTelemetry export** — one span per node, wired to the existing trace ids
- [ ] **Grafana dashboard** — a versioned JSON panel using the metrics above
- [ ] **Per-tenant quotas** — the budget ledger already has the right shape for it

## Contributing

Issues and pull requests are welcome. Before opening a PR:

1. `./mvnw verify` must pass.
2. New behaviour needs a test that fails without it. For scheduler changes, that test belongs in
   `ExecutionEngineTest`.
3. Comments and commit messages in English, please.
4. Keep `loomvault-core` free of Spring and JDBC. If you need a new capability, add a port in
   `io.loomvault.core.port` and implement it in an outer module.

Good first issues are labelled [`good first issue`](https://github.com/<your-account>/loomvault/labels/good%20first%20issue).

## Documentation

| Document | Contents |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Scheduler design, replay semantics, concurrency model, trade-offs |
| [`docs/DEMO.md`](docs/DEMO.md) | A 5-minute script that exercises every headline feature |
| [`docs/WORKFLOW-SPEC.md`](docs/WORKFLOW-SPEC.md) | Workflow definition reference (nodes, edges, budget, expressions) |

## License

Apache License 2.0 — see [LICENSE](LICENSE).
